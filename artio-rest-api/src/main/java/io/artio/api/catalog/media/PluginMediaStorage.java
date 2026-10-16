package io.artio.api.catalog.media;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Core-mediated object storage with a frozen primary/replica routing plan per object operation. */
@Component
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
public final class PluginMediaStorage implements MediaStorage {
    private static final int CHUNK_BYTES = 512 * 1024;
    private final CommerceCapabilityInvoker invoker;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MediaStorageMigrationSourceFactory sourceFactory;

    @Autowired
    public PluginMediaStorage(CommerceCapabilityInvoker invoker, JdbcTemplate jdbc, ObjectMapper mapper,
            MediaStorageMigrationSourceFactory sourceFactory) {
        this.invoker = invoker;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.sourceFactory = sourceFactory;
    }

    public PluginMediaStorage(CommerceCapabilityInvoker invoker, JdbcTemplate jdbc, ObjectMapper mapper) {
        this(invoker, jdbc, mapper, null);
    }

    @Override
    public void store(String key, byte[] content) {
        String checksum = sha256(content);
        var existing = jdbc.query("SELECT content_checksum,object_state FROM catalog_media_storage_objects WHERE storage_key=?",
                (rs, row) -> new ExistingObject(rs.getString("content_checksum"), rs.getString("object_state")), key)
                .stream().findFirst();
        if (existing.isPresent()) {
            if (!"ACTIVE".equals(existing.get().state()) || !checksum.equalsIgnoreCase(existing.get().checksum()))
                throw new IllegalStateException("Object storage key already belongs to different or tombstoned content");
            if (!java.util.Arrays.equals(content, read(key)))
                throw new IllegalStateException("Existing object storage content does not match the requested checksum");
            return;
        }
        int chunks = Math.max(1, (content.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        CommerceCapabilityInvoker.Invocation placement = null;
        for (int index = 0; index < chunks; index++) {
            int from = index * CHUNK_BYTES;
            byte[] chunk = java.util.Arrays.copyOfRange(content, from, Math.min(content.length, from + CHUNK_BYTES));
            var result = invoke("put", stableId("put:" + index, key), Map.of(
                    "key", key, "routingKey", stableId("put:0", key), "uploadId", stableId("upload", key), "chunkIndex", index, "totalChunks", chunks,
                    "chunkBase64", Base64.getEncoder().encodeToString(chunk), "sha256", checksum,
                    "complete", index == chunks - 1));
            placement = result;
            JsonNode response = result.response();
            JsonNode receipts = response.path("receipts");
            if (!receipts.isArray() || receipts.isEmpty() || !response.path("allReplicasAcknowledged").asBoolean(false))
                throw new IllegalStateException("Object storage replicas have not all acknowledged the uploaded media chunk");
            if (index == chunks - 1) for (JsonNode receipt : receipts) {
                if (!checksum.equalsIgnoreCase(receipt.path("sha256").asText()))
                    throw new IllegalStateException("Object storage replica checksum does not match the uploaded media");
            }
        }
        persistPlacement(key, content.length, checksum, placement);
    }

    @Override
    public byte[] read(String key) {
        java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
        int totalChunks = -1;
        String expectedChecksum;
        try {
            expectedChecksum = jdbc.queryForObject("SELECT content_checksum FROM catalog_media_storage_objects WHERE storage_key=? AND object_state='ACTIVE'",
                    String.class, key);
        } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
            if (sourceFactory == null) throw new IllegalStateException("Object storage metadata is missing or the media object is tombstoned", missing);
            MediaStorage source = sourceFactory.open();
            try {
                byte[] legacy = source.read(key);
                if (legacy == null || legacy.length == 0 || legacy.length > ProductMediaService.MAX_UPLOAD_BYTES)
                    throw new IllegalStateException("Legacy catalog media object has an invalid size");
                return legacy;
            } finally {
                if (source instanceof AutoCloseable closeable) try { closeable.close(); }
                catch (Exception closeFailure) { /* Preserve a successful legacy read; migration owns retryable close failures. */ }
            }
        }
        try {
            for (int index = 0; totalChunks < 0 || index < totalChunks; index++) {
                if (index >= 20) throw new IllegalStateException("Object storage returned too many media chunks");
                JsonNode response = invoke("get", stableId("get:" + index, key),
                        Map.of("key", key, "routingKey", stableId("put:0", key), "chunkIndex", index,
                                "expectedChecksum", expectedChecksum)).response();
                String encoded = response.path("chunkBase64").asText(null);
                String checksum = response.path("sha256").asText(null);
                int responseIndex = response.path("chunkIndex").asInt(-1);
                int responseChunks = response.path("totalChunks").asInt(-1);
                if (encoded == null || checksum == null || responseIndex != index || responseChunks < 1 || responseChunks > 20
                        || totalChunks >= 0 && totalChunks != responseChunks || !expectedChecksum.equalsIgnoreCase(checksum))
                    throw new IllegalStateException("Object storage returned inconsistent media chunks");
                byte[] chunk = Base64.getDecoder().decode(encoded);
                if (chunk.length > CHUNK_BYTES) throw new IllegalStateException("Object storage media chunk exceeds its bound");
                content.writeBytes(chunk);
                totalChunks = responseChunks;
                if (content.size() > ProductMediaService.MAX_UPLOAD_BYTES)
                    throw new IllegalStateException("Object storage media object exceeds the supported size");
            }
            byte[] bytes = content.toByteArray();
            if (expectedChecksum == null || !expectedChecksum.equalsIgnoreCase(sha256(bytes)))
                throw new IllegalStateException("Object storage media checksum verification failed");
            return bytes;
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Object storage returned invalid media encoding", malformed);
        }
    }

    @Override
    public void delete(String key) {
        UUID tombstone = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE catalog_media_storage_objects SET object_state='DELETE_PENDING',tombstone_id=?,updated_at=now() "
                + "WHERE storage_key=? AND object_state<>'TOMBSTONED'", tombstone, key);
        finishDelete(key, tombstone);
    }

    /** Replays durable deletion intents after process restart or a transient provider failure. */
    @Scheduled(fixedDelayString = "${artio.catalog.media.tombstone-recovery-interval-ms:5000}")
    public void recoverPendingDeletes() {
        recoverPendingDeletes(25);
    }

    public int recoverPendingDeletes(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Tombstone recovery batch must be between 1 and 100");
        var pending = jdbc.query("SELECT storage_key,tombstone_id FROM catalog_media_storage_objects "
                        + "WHERE object_state='DELETE_PENDING' AND tombstone_id IS NOT NULL ORDER BY updated_at,storage_key LIMIT ?",
                (rs, row) -> new PendingDelete(rs.getString("storage_key"), rs.getObject("tombstone_id", UUID.class)), limit);
        for (PendingDelete item : pending) {
            try {
                finishDelete(item.storageKey(), item.tombstoneId());
            } catch (RuntimeException failure) {
                // Keep the durable intent for the next scheduled attempt; one failed provider must not block the batch.
            }
        }
        return pending.size();
    }

    private void finishDelete(String key, UUID tombstone) {
        JsonNode response = invoke("delete", stableId("delete", key), Map.of("key", key,
                "routingKey", stableId("put:0", key), "tombstoneId", tombstone.toString())).response();
        JsonNode receipts = response.path("receipts");
        if (!receipts.isArray() || receipts.isEmpty() || !response.path("allReplicasAcknowledged").asBoolean(false))
            throw new IllegalStateException("Object storage replicas have not all acknowledged the deletion tombstone");
        jdbc.update("UPDATE catalog_media_storage_objects SET object_state='TOMBSTONED',updated_at=now() "
                + "WHERE storage_key=? AND object_state='DELETE_PENDING' AND tombstone_id=?", key, tombstone);
    }

    private CommerceCapabilityInvoker.Invocation invoke(String operation, String idempotencyKey, Object payload) {
        var result = invoker.invoke("storage.object.v1", operation, Map.of(), null, true, idempotencyKey, payload);
        if (result == null) throw new IllegalStateException("No enabled object storage binding is configured");
        return result;
    }

    private void persistPlacement(String key, int byteLength, String checksum, CommerceCapabilityInvoker.Invocation placement) {
        if (placement == null) throw new IllegalStateException("Object storage did not return a placement owner");
        JsonNode response = placement.response();
        JsonNode targetIds = response.path("targetInstanceIds");
        JsonNode receipts = response.path("receipts");
        if (!targetIds.isArray() || targetIds.isEmpty() || !receipts.isArray() || targetIds.size() != receipts.size()
                || !placement.owner().instanceId().toString().equals(targetIds.get(0).asText()))
            throw new IllegalStateException("Object storage did not return complete placement metadata");
        java.util.List<String> replicas = new java.util.ArrayList<>();
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (int index = 0; index < receipts.size(); index++) {
            String instanceId = targetIds.get(index).asText();
            String receiptChecksum = receipts.get(index).path("sha256").asText(null);
            String versionId = receipts.get(index).path("versionId").asText(null);
            if (!checksum.equalsIgnoreCase(receiptChecksum) || versionId == null || versionId.isBlank() || versionId.length() > 256)
                throw new IllegalStateException("Object storage returned invalid checksum or version metadata");
            versions.put(instanceId, versionId);
            if (index > 0) replicas.add(instanceId);
        }
        try {
            int updated = jdbc.update("INSERT INTO catalog_media_storage_objects(storage_key,content_checksum,byte_length,primary_instance_id,"
                            + "primary_release_id,primary_configuration_generation,primary_account_fingerprint,replica_instance_ids,provider_versions) "
                            + "VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(storage_key) DO UPDATE SET "
                            + "content_checksum=EXCLUDED.content_checksum,byte_length=EXCLUDED.byte_length,primary_instance_id=EXCLUDED.primary_instance_id,"
                            + "primary_release_id=EXCLUDED.primary_release_id,primary_configuration_generation=EXCLUDED.primary_configuration_generation,"
                            + "primary_account_fingerprint=EXCLUDED.primary_account_fingerprint,replica_instance_ids=EXCLUDED.replica_instance_ids,"
                            + "provider_versions=EXCLUDED.provider_versions,updated_at=now() WHERE catalog_media_storage_objects.object_state='ACTIVE' "
                            + "AND catalog_media_storage_objects.content_checksum=EXCLUDED.content_checksum", key, checksum, byteLength, placement.owner().instanceId(),
                    placement.owner().releaseId(), placement.owner().configurationGeneration(), placement.owner().externalAccountFingerprint(),
                    mapper.writeValueAsString(replicas), mapper.writeValueAsString(versions));
            if (updated != 1) throw new IllegalStateException("Object storage metadata conflicts with existing object state or checksum");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Object storage placement metadata could not be encoded", failure);
        }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    private static String stableId(String operation, String key) {
        return UUID.nameUUIDFromBytes(("artio-media:" + operation + ":" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record ExistingObject(String checksum, String state) { }
    private record PendingDelete(String storageKey, UUID tombstoneId) { }
}
