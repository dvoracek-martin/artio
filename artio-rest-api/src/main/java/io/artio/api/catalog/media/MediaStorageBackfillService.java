package io.artio.api.catalog.media;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Copies legacy catalog blobs to plugin storage and verifies every destination read without deleting source data. */
@Service
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
public class MediaStorageBackfillService {
    private static final int MAX_BATCH_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final MediaStorage destination;
    private final MediaStorageMigrationSourceFactory sourceFactory;

    public MediaStorageBackfillService(JdbcTemplate jdbc, MediaStorage destination, MediaStorageMigrationSourceFactory sourceFactory) {
        this.jdbc = jdbc;
        this.destination = destination;
        this.sourceFactory = sourceFactory;
    }

    public BackfillResult backfill(String afterStorageKey, int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) throw new IllegalArgumentException("Backfill batch size must be between 1 and 100");
        if (afterStorageKey != null && !afterStorageKey.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(jpg|png)"))
            throw new IllegalArgumentException("Backfill cursor is invalid");
        List<String> keys = jdbc.query("SELECT m.storage_key FROM catalog_product_media m "
                        + "LEFT JOIN catalog_media_storage_objects s ON s.storage_key=m.storage_key AND s.object_state='ACTIVE' "
                        + "WHERE s.storage_key IS NULL AND (?::varchar IS NULL OR m.storage_key>?) "
                        + "ORDER BY m.storage_key LIMIT ?",
                (rs, row) -> rs.getString(1), afterStorageKey, afterStorageKey, limit);
        if (keys.isEmpty()) return new BackfillResult(0, 0, 0, missingCount() == 0, afterStorageKey, List.of());

        MediaStorage source = sourceFactory.open();
        List<Failure> failures = new ArrayList<>();
        int migrated = 0;
        String cursor = afterStorageKey;
        try {
            for (String key : keys) {
                try {
                    byte[] sourceBytes = source.read(key);
                    if (sourceBytes == null || sourceBytes.length == 0 || sourceBytes.length > ProductMediaService.MAX_UPLOAD_BYTES)
                        throw new IllegalStateException("source_size_invalid");
                    String checksum = sha256(sourceBytes);
                    destination.store(key, sourceBytes);
                    byte[] verified = destination.read(key);
                    if (verified.length != sourceBytes.length || !checksum.equals(sha256(verified)))
                        throw new IllegalStateException("destination_checksum_mismatch");
                    migrated++;
                    cursor = key;
                } catch (RuntimeException failure) {
                    failures.add(new Failure(key, safeCode(failure)));
                    break;
                }
            }
        } finally {
            if (source instanceof AutoCloseable closeable) {
                try { closeable.close(); }
                catch (Exception failure) { failures.add(new Failure(cursor, safeCode(failure))); }
            }
        }
        return new BackfillResult(keys.size(), migrated, failures.size(), missingCount() == 0, cursor, List.copyOf(failures));
    }

    private long missingCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM catalog_product_media m "
                + "LEFT JOIN catalog_media_storage_objects s ON s.storage_key=m.storage_key AND s.object_state='ACTIVE' "
                + "WHERE s.storage_key IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    private static String safeCode(Throwable failure) {
        String value = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
        return value.isBlank() ? "StorageFailure" : value.substring(0, Math.min(64, value.length()));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    public record Failure(String storageKey, String code) { }
    public record BackfillResult(int inspected, int migrated, int failed, boolean complete, String nextCursor,
            List<Failure> failures) { }
}
