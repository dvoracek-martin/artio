package io.artio.api.catalog.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@Transactional
public class ProductMediaService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductMediaService.class);
    public static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final int MAX_MEDIA_PER_PRODUCT = 100;
    private static final long MAX_PIXELS = 20_000_000L;
    private static final int MAX_DIMENSION = 10_000;
    private final JdbcTemplate jdbc;
    private final MediaStorage storage;

    public ProductMediaService(JdbcTemplate jdbc, MediaStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public MediaView upload(UUID productId, byte[] uploaded, String altText) {
        lockProduct(productId);
        Integer currentCount = jdbc.queryForObject("SELECT count(*) FROM catalog_product_media WHERE product_id=?", Integer.class, productId);
        if (currentCount != null && currentCount >= MAX_MEDIA_PER_PRODUCT) throw new ResponseStatusException(HttpStatus.CONFLICT, "A product cannot have more than 100 images");
        if (uploaded == null || uploaded.length == 0) throw new IllegalArgumentException("Choose an image file");
        if (uploaded.length > MAX_UPLOAD_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image upload is limited to 10 MiB");
        String cleanAlt = altText == null ? "" : altText.trim();
        if (cleanAlt.length() > 500) throw new IllegalArgumentException("Alternative text cannot exceed 500 characters");
        SanitizedImage image = sanitize(uploaded);
        UUID id = UUID.randomUUID();
        String key = id + (image.contentType().equals("image/jpeg") ? ".jpg" : ".png");
        storage.store(key, image.content());
        cleanupIfRolledBack(key);
        try {
            Integer nextPosition = jdbc.queryForObject("SELECT COALESCE(MAX(position)+1,0) FROM catalog_product_media WHERE product_id=?", Integer.class, productId);
            jdbc.update("INSERT INTO catalog_product_media(id,product_id,storage_key,content_type,width,height,alt_text,position) VALUES (?,?,?,?,?,?,?,?)",
                    id, productId, key, image.contentType(), image.width(), image.height(), cleanAlt, nextPosition == null ? 0 : nextPosition);
        } catch (RuntimeException failure) {
            try { storage.delete(key); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return findOne(productId, id);
    }

    /** Copy sanitized image blobs and metadata to a new draft product within the caller's transaction. */
    public List<MediaView> copyToProduct(UUID sourceProductId, UUID targetProductId) {
        java.util.stream.Stream.of(sourceProductId, targetProductId)
                .sorted(java.util.Comparator.comparing(UUID::toString)).forEach(this::lockProduct);
        List<MediaCopyRow> source = jdbc.query("SELECT storage_key,content_type,alt_text,position FROM catalog_product_media "
                        + "WHERE product_id=? ORDER BY position,created_at,id",
                (rs, row) -> new MediaCopyRow(rs.getString("storage_key"), rs.getString("content_type"),
                        rs.getString("alt_text"), rs.getInt("position")), sourceProductId);
        if (source.size() > MAX_MEDIA_PER_PRODUCT) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The source product exceeds the image limit and cannot be duplicated");
        for (MediaCopyRow item : source) {
            SanitizedImage image = sanitize(storage.read(item.storageKey()));
            String extension = image.contentType().equals("image/jpeg") ? ".jpg" : ".png";
            UUID mediaId = UUID.randomUUID();
            String key = mediaId + extension;
            storage.store(key, image.content());
            cleanupIfRolledBack(key);
            try {
                jdbc.update("INSERT INTO catalog_product_media(id,product_id,storage_key,content_type,width,height,alt_text,position) "
                                + "VALUES (?,?,?,?,?,?,?,?)",
                        mediaId, targetProductId, key, image.contentType(), image.width(), image.height(), item.altText(), item.position());
            } catch (RuntimeException failure) {
                try { storage.delete(key); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        return findByProduct(targetProductId);
    }

    public MediaView replace(UUID productId, UUID mediaId, byte[] uploaded, String altText) {
        lockProduct(productId);
        if (uploaded == null || uploaded.length == 0) throw new IllegalArgumentException("Choose an image file");
        if (uploaded.length > MAX_UPLOAD_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image upload is limited to 10 MiB");
        String cleanAlt = altText == null ? "" : altText.trim();
        if (cleanAlt.length() > 500) throw new IllegalArgumentException("Alternative text cannot exceed 500 characters");
        SanitizedImage image = sanitize(uploaded);
        MediaRow current = jdbc.query("SELECT storage_key,content_type FROM catalog_product_media WHERE product_id=? AND id=?",
                (rs, n) -> new MediaRow(rs.getString("storage_key"), rs.getString("content_type")), productId, mediaId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product image not found"));
        String replacementKey = UUID.randomUUID() + (image.contentType().equals("image/jpeg") ? ".jpg" : ".png");
        storage.store(replacementKey, image.content());
        cleanupIfRolledBack(replacementKey);
        try {
            jdbc.update("UPDATE catalog_product_media SET storage_key=?,content_type=?,width=?,height=?,alt_text=?,updated_at=now() WHERE product_id=? AND id=?",
                    replacementKey, image.contentType(), image.width(), image.height(), cleanAlt, productId, mediaId);
        } catch (RuntimeException failure) {
            try { storage.delete(replacementKey); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        removeAfterCommit(current.storageKey());
        return findOne(productId, mediaId);
    }

    @Transactional(readOnly = true)
    public List<MediaView> findByProduct(UUID productId) {
        requireProduct(productId);
        return jdbc.query("SELECT id,product_id,content_type,width,height,alt_text,position,created_at,updated_at FROM catalog_product_media WHERE product_id=? ORDER BY position,created_at,id",
                (rs, row) -> new MediaView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getString("content_type"), rs.getInt("width"), rs.getInt("height"), rs.getString("alt_text"),
                        rs.getInt("position"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), productId);
    }

    @Transactional(readOnly = true)
    public MediaContent content(UUID productId, UUID mediaId) {
        MediaRow row = jdbc.query("SELECT storage_key,content_type FROM catalog_product_media WHERE product_id=? AND id=?",
                (rs, n) -> new MediaRow(rs.getString("storage_key"), rs.getString("content_type")), productId, mediaId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product image not found"));
        return new MediaContent(row.contentType(), storage.read(row.storageKey()));
    }

    public void reorder(UUID productId, List<UUID> orderedMediaIds) {
        lockProduct(productId);
        List<UUID> current = jdbc.query("SELECT id FROM catalog_product_media WHERE product_id=? ORDER BY position,created_at,id",
                (rs, n) -> rs.getObject(1, UUID.class), productId);
        if (orderedMediaIds == null || orderedMediaIds.size() != current.size()
                || orderedMediaIds.stream().distinct().count() != orderedMediaIds.size()
                || !current.containsAll(orderedMediaIds)) {
            throw new IllegalArgumentException("Media order must contain every product image exactly once");
        }
        for (int index = 0; index < orderedMediaIds.size(); index++) {
            jdbc.update("UPDATE catalog_product_media SET position=? WHERE product_id=? AND id=?", index, productId, orderedMediaIds.get(index));
        }
    }

    public void delete(UUID productId, UUID mediaId) {
        lockProduct(productId);
        String key = jdbc.query("SELECT storage_key FROM catalog_product_media WHERE product_id=? AND id=?",
                (rs, n) -> rs.getString(1), productId, mediaId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product image not found"));
        jdbc.update("DELETE FROM catalog_product_media WHERE product_id=? AND id=?", productId, mediaId);
        removeAfterCommit(key);
    }

    static SanitizedImage sanitize(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IllegalArgumentException("Only JPEG and PNG images are supported");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("Only JPEG and PNG images are supported");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toUpperCase(java.util.Locale.ROOT);
                String contentType = switch (format) {
                    case "JPEG", "JPG" -> "image/jpeg";
                    case "PNG" -> "image/png";
                    default -> throw new IllegalArgumentException("Only JPEG and PNG images are supported");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("Image dimensions exceed the supported limit");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IllegalArgumentException("Image data is invalid");
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(decoded, contentType.equals("image/jpeg") ? "jpeg" : "png", output)) {
                        throw new IllegalArgumentException("Image format could not be safely encoded");
                    }
                    byte[] sanitized = output.toByteArray();
                    if (sanitized.length > MAX_UPLOAD_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Processed image exceeds 10 MiB");
                    return new SanitizedImage(contentType, width, height, sanitized);
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Image data is invalid", invalid);
        }
    }

    private MediaView findOne(UUID productId, UUID id) {
        return jdbc.query("SELECT id,product_id,content_type,width,height,alt_text,position,created_at,updated_at FROM catalog_product_media WHERE product_id=? AND id=?",
                (rs, row) -> new MediaView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("content_type"),
                        rs.getInt("width"), rs.getInt("height"), rs.getString("alt_text"), rs.getInt("position"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), productId, id)
                .stream().findFirst().orElseThrow();
    }

    private void removeAfterCommit(String key) {
        Runnable removeBlob = () -> storage.delete(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { removeBlob.run(); }
                    catch (RuntimeException failure) { log.error("Catalog media blob cleanup failed after database commit", failure); }
                }
            });
        } else {
            removeBlob.run();
        }
    }

    private void cleanupIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try { storage.delete(key); }
                    catch (RuntimeException failure) { log.error("Catalog media rollback cleanup failed", failure); }
                }
            }
        });
    }

    private void requireProduct(UUID productId) {
        if (jdbc.query("SELECT 1 FROM catalog_product WHERE id=?", (rs, n) -> rs.getInt(1), productId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
    }

    private void lockProduct(UUID productId) {
        if (jdbc.query("SELECT id FROM catalog_product WHERE id=? FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), productId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
    }

    private record MediaRow(String storageKey, String contentType) { }
    private record MediaCopyRow(String storageKey, String contentType, String altText, int position) { }
    record SanitizedImage(String contentType, int width, int height, byte[] content) { }
    public record MediaContent(String contentType, byte[] bytes) { }
    public record MediaView(UUID id, UUID productId, String contentType, int width, int height,
                            String altText, int position, Instant createdAt, Instant updatedAt) { }
}
