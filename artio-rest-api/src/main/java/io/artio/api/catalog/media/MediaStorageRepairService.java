package io.artio.api.catalog.media;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Revalidates and repairs each active plugin placement without changing its historical targets. */
@Service
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
public class MediaStorageRepairService {
    private static final int MAX_BATCH_SIZE = 100;
    private static final String CURSOR_PATTERN = "[0-9a-f]{8}(-[0-9a-f]{4}){3}\\.(jpg|png)";
    private final JdbcTemplate jdbc;
    private final PluginMediaStorage storage;

    public MediaStorageRepairService(JdbcTemplate jdbc, MediaStorage storage) {
        this.jdbc = jdbc;
        if (!(storage instanceof PluginMediaStorage pluginStorage))
            throw new IllegalArgumentException("Plugin media storage is required for repair");
        this.storage = pluginStorage;
    }

    public RepairResult repair(String afterStorageKey, int limit, UUID repairRunId) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) throw new IllegalArgumentException("Repair batch size must be between 1 and 100");
        if (repairRunId == null) throw new IllegalArgumentException("A repair run ID is required");
        if (afterStorageKey != null && !afterStorageKey.matches(CURSOR_PATTERN))
            throw new IllegalArgumentException("Repair cursor is invalid");
        List<String> keys = jdbc.query("SELECT storage_key FROM catalog_media_storage_objects "
                        + "WHERE object_state='ACTIVE' AND (?::varchar IS NULL OR storage_key>?) "
                        + "ORDER BY storage_key LIMIT ?", (rs, row) -> rs.getString(1), afterStorageKey, afterStorageKey, limit);
        if (keys.isEmpty()) return new RepairResult(0, 0, 0, true, afterStorageKey, List.of());
        int repaired = 0;
        String cursor = afterStorageKey;
        List<Failure> failures = new ArrayList<>();
        for (String key : keys) {
            try {
                storage.repair(key, repairRunId);
                repaired++;
                cursor = key;
            } catch (RuntimeException failure) {
                failures.add(new Failure(key, safeCode(failure)));
                break;
            }
        }
        boolean complete = failures.isEmpty() && !hasMore(cursor);
        return new RepairResult(repaired + failures.size(), repaired, failures.size(), complete, cursor, List.copyOf(failures));
    }

    private boolean hasMore(String cursor) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM catalog_media_storage_objects "
                + "WHERE object_state='ACTIVE' AND (?::varchar IS NULL OR storage_key>?)", Long.class, cursor, cursor);
        return count != null && count > 0;
    }

    private static String safeCode(Throwable failure) {
        String value = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
        return value.isBlank() ? "StorageFailure" : value.substring(0, Math.min(64, value.length()));
    }

    public record Failure(String storageKey, String code) { }
    public record RepairResult(int inspected, int repaired, int failed, boolean complete, String nextCursor,
            List<Failure> failures) { }
}
