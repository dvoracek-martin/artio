package io.artio.api.catalog.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/catalog/media/storage-status")
public class MediaStorageStatusController {
    private final ProductMediaService media;
    private final JdbcTemplate jdbc;
    private final String storageMode;
    private final String migrationSource;

    public MediaStorageStatusController(ProductMediaService media, JdbcTemplate jdbc,
            @Value("${artio.catalog.media.storage:local}") String storageMode,
            @Value("${artio.catalog.media.migration.source:local}") String migrationSource) {
        this.media = media;
        this.jdbc = jdbc;
        this.storageMode = storageMode;
        this.migrationSource = migrationSource;
    }

    @GetMapping
    public StorageStatus getStatus(JwtAuthenticationToken auth) {
        Counts counts = jdbc.queryForObject("SELECT (SELECT count(*) FROM catalog_product_media) AS media_count, "
                        + "(SELECT count(*) FROM catalog_media_storage_objects WHERE object_state='ACTIVE') AS active_count, "
                        + "(SELECT count(*) FROM catalog_media_storage_objects WHERE object_state='DELETE_PENDING') AS delete_pending, "
                        + "(SELECT count(*) FROM catalog_media_storage_objects WHERE object_state='TOMBSTONED') AS tombstoned",
                (rs, row) -> new Counts(rs.getLong("media_count"), rs.getLong("active_count"),
                        rs.getLong("delete_pending"), rs.getLong("tombstoned")));
        long pendingMigration = "plugins".equals(storageMode)
                ? jdbc.queryForObject("SELECT count(*) FROM catalog_product_media m LEFT JOIN catalog_media_storage_objects s "
                        + "ON s.storage_key=m.storage_key AND s.object_state='ACTIVE' WHERE s.storage_key IS NULL", Long.class)
                : 0;
        return new StorageStatus(media.isUploadAvailable(), "storage", storageMode, migrationSource,
                counts.mediaCount(), counts.activeCount(), pendingMigration, counts.deletePending(), counts.tombstoned(),
                "plugins".equals(storageMode), auth != null && auth.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_SUPERADMIN".equals(authority.getAuthority())));
    }

    private record Counts(long mediaCount, long activeCount, long deletePending, long tombstoned) { }
    public record StorageStatus(boolean uploadAvailable, String installCategory, String storageMode,
            String migrationSource, long mediaCount, long activePluginObjects, long pendingMigration,
            long deletePending, long tombstoned, boolean migrationAvailable, boolean canManageStorage) { }
}
