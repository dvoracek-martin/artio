package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MediaStorageBackfillServiceTests {
    private static final String KEY = "f4b31b5d-44ae-4c20-b08c-4c03c59a1bc7.png";

    @Test
    void copiesAndVerifiesEachBlobWithoutDeletingTheConfiguredSource(@TempDir Path directory) throws Exception {
        byte[] bytes = "source-media".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path sourcePath = directory.resolve(KEY);
        Files.write(sourcePath, bytes);
        MemoryStorage destination = new MemoryStorage(false);
        var service = new MediaStorageBackfillService(new OneKeyJdbcTemplate(KEY, 0L), destination,
                new MediaStorageMigrationSourceFactory("local", directory.toString(), "", "us-east-1", "", true, "products"));

        var result = service.backfill(null, 50);

        assertThat(result).isEqualTo(new MediaStorageBackfillService.BackfillResult(1, 1, 0, true, KEY, List.of()));
        assertThat(destination.read(KEY)).isEqualTo(bytes);
        assertThat(Files.readAllBytes(sourcePath)).isEqualTo(bytes);
        assertThat(destination.deleted).isZero();
    }

    @Test
    void reportsChecksumMismatchAndLeavesCursorBeforeFailedObjectForSafeRetry(@TempDir Path directory) throws Exception {
        Files.write(directory.resolve(KEY), "source-media".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MemoryStorage destination = new MemoryStorage(true);
        var service = new MediaStorageBackfillService(new OneKeyJdbcTemplate(KEY, 1L), destination,
                new MediaStorageMigrationSourceFactory("local", directory.toString(), "", "us-east-1", "", true, "products"));

        var result = service.backfill(null, 1);

        assertThat(result.inspected()).isEqualTo(1);
        assertThat(result.migrated()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.storageKey()).isEqualTo(KEY);
            assertThat(failure.code()).isEqualTo("IllegalStateException");
        });
        assertThat(Files.exists(directory.resolve(KEY))).isTrue();
        assertThat(destination.deleted).isZero();
    }

    @Test
    void boundsBatchAndCursorInputsBeforeAccessingStorage(@TempDir Path directory) {
        var service = new MediaStorageBackfillService(new OneKeyJdbcTemplate(KEY, 1L), new MemoryStorage(false),
                new MediaStorageMigrationSourceFactory("local", directory.toString(), "", "us-east-1", "", true, "products"));

        assertThatThrownBy(() -> service.backfill(null, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.backfill("../../private.png", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class OneKeyJdbcTemplate extends JdbcTemplate {
        private final String key;
        private final long missingCount;
        private OneKeyJdbcTemplate(String key, long missingCount) { this.key = key; this.missingCount = missingCount; }
        @Override @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            return sql.contains("SELECT m.storage_key") ? (List<T>) List.of(key) : List.of();
        }
        @Override public <T> T queryForObject(String sql, Class<T> requiredType) {
            return requiredType.cast(missingCount);
        }
    }

    private static final class MemoryStorage implements MediaStorage {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final boolean corruptReads;
        private int deleted;
        private MemoryStorage(boolean corruptReads) { this.corruptReads = corruptReads; }
        @Override public void store(String key, byte[] content) { objects.put(key, content.clone()); }
        @Override public byte[] read(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IllegalStateException("missing");
            byte[] result = bytes.clone();
            if (corruptReads) result[0] ^= 1;
            return result;
        }
        @Override public void delete(String key) { deleted++; objects.remove(key); }
    }
}
