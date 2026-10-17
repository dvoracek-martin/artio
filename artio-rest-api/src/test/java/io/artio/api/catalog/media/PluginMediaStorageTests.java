package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class PluginMediaStorageTests {
    private final CommerceCapabilityInvoker invoker = mock(CommerceCapabilityInvoker.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final PluginMediaStorage storage = new PluginMediaStorage(invoker, jdbc, mapper);
    private final String key = UUID.randomUUID() + ".png";
    private final CommerceCapabilityInvoker.Owner owner = new CommerceCapabilityInvoker.Owner(UUID.randomUUID(), 7L, 2,
            "a".repeat(64), Map.of());

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void storageMetadataStartsEmpty() {
        org.mockito.Mockito.doReturn(java.util.List.of()).when(jdbc).query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class));
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(1);
    }

    @Test
    void storesOnlyAfterEveryPlacementAcknowledgesTheSameChecksum() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"%s","versionId":"primary-v1"},{"sha256":"%s","versionId":"replica-v1"}],"allReplicasAcknowledged":true,"targetInstanceIds":["%s","%s"]}
                        """.formatted(checksum, checksum, owner.instanceId(), UUID.randomUUID())), owner));

        storage.store(key, bytes);

        verify(invoker).invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void rejectsPartialOrCorruptReplicaWrites() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"bad","versionId":"v1"}],"allReplicasAcknowledged":false,"targetInstanceIds":["%s"]}
                        """.formatted(owner.instanceId())), owner));

        assertThatThrownBy(() -> storage.store(key, bytes)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void splitsLargeObjectsIntoIndividuallyBoundedDurableChunks() throws Exception {
        byte[] bytes = new byte[600 * 1024];
        java.util.Arrays.fill(bytes, (byte) 7);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"%s","versionId":"primary-v1"}],"allReplicasAcknowledged":true,"targetInstanceIds":["%s"]}
                        """.formatted(checksum, owner.instanceId())), owner));

        storage.store(key, bytes);

        verify(invoker, times(2)).invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void verifiesContentChecksumOnReadAndRequiresTombstoneAcknowledgementsOnDelete() throws Exception {
        byte[] bytes = "media-bytes".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_checksum"), eq(String.class), eq(key))).thenReturn(checksum);
        when(invoker.invoke(eq("storage.object.v1"), eq("get"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"chunkBase64":"%s","sha256":"%s","chunkIndex":0,"totalChunks":1}
                        """.formatted(java.util.Base64.getEncoder().encodeToString(bytes), checksum)), owner));
        assertThat(storage.read(key)).isEqualTo(bytes);

        when(invoker.invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"deleted":true}],"allReplicasAcknowledged":true}
                        """), owner));
        storage.delete(key);
        verify(invoker).invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), any(), any());
    }

    @Test
    void servesUnbackfilledMediaFromTheConfiguredSourceUntilVerifiedCopyExists() {
        byte[] legacyBytes = "legacy-media".getBytes(StandardCharsets.UTF_8);
        var sourceFactory = mock(MediaStorageMigrationSourceFactory.class);
        var source = mock(MediaStorage.class);
        when(sourceFactory.open()).thenReturn(source);
        when(source.read(key)).thenReturn(legacyBytes);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_checksum"), eq(String.class), eq(key)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        var withFallback = new PluginMediaStorage(invoker, jdbc, mapper, sourceFactory);

        assertThat(withFallback.read(key)).isEqualTo(legacyBytes);
        verify(source).read(key);
        verify(source, org.mockito.Mockito.never()).delete(key);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void repairsTheFrozenPlacementOnlyAfterRecoveringChecksumVerifiedBytes() throws Exception {
        byte[] bytes = "repairable-media".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID repairRunId = UUID.randomUUID();
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.jdbc.core.RowMapper mapper = invocation.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            when(row.getString("content_checksum")).thenReturn(checksum);
            when(row.getInt("byte_length")).thenReturn(bytes.length);
            return java.util.List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains("content_checksum,byte_length"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(key));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("content_checksum"), eq(String.class), eq(key))).thenReturn(checksum);
        when(invoker.invoke(eq("storage.object.v1"), eq("get"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"chunkBase64":"%s","sha256":"%s","chunkIndex":0,"totalChunks":1}
                        """.formatted(java.util.Base64.getEncoder().encodeToString(bytes), checksum)), owner));
        when(invoker.invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"sha256":"%s","versionId":"repaired-v1"}],"allReplicasAcknowledged":true,"targetInstanceIds":["%s"]}
                        """.formatted(checksum, owner.instanceId())), owner));

        storage.repair(key, repairRunId);

        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(invoker).invoke(eq("storage.object.v1"), eq("put"), eq(Map.of()), eq(null), eq(true), any(), payload.capture());
        assertThat(mapper.valueToTree(payload.getValue()).path("routingKey").asText())
                .isEqualTo(UUID.nameUUIDFromBytes(("artio-media:put:0:" + key).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(mapper.valueToTree(payload.getValue()).path("chunkBase64").asText())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(bytes));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void recoversADeleteIntentPersistedBeforeCoreRestart() throws Exception {
        UUID tombstone = UUID.randomUUID();
        org.mockito.Mockito.doAnswer(invocation -> {
            org.springframework.jdbc.core.RowMapper mapper = invocation.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            when(row.getString("storage_key")).thenReturn(key);
            when(row.getObject("tombstone_id", UUID.class)).thenReturn(tombstone);
            return java.util.List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(org.mockito.ArgumentMatchers.contains("object_state='DELETE_PENDING'"),
                any(org.springframework.jdbc.core.RowMapper.class), eq(25));
        when(invoker.invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), any(), any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(mapper.readTree("""
                        {"receipts":[{"deleted":true}],"allReplicasAcknowledged":true}
                        """), owner));

        int recovered = storage.recoverPendingDeletes(25);

        assertThat(recovered).isEqualTo(1);
        org.mockito.ArgumentCaptor<Object[]> updateArgs = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("object_state='TOMBSTONED'"), updateArgs.capture());
        assertThat(updateArgs.getValue()).containsExactly(key, tombstone);
        String idempotencyKey = UUID.nameUUIDFromBytes(("artio-media:delete:" + key).getBytes(StandardCharsets.UTF_8)).toString();
        verify(invoker).invoke(eq("storage.object.v1"), eq("delete"), eq(Map.of()), eq(null), eq(true), eq(idempotencyKey),
                eq(Map.of("key", key, "routingKey", UUID.nameUUIDFromBytes(("artio-media:put:0:" + key)
                        .getBytes(StandardCharsets.UTF_8)).toString(), "tombstoneId", tombstone.toString())));
    }
}
