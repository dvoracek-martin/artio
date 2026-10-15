package io.artio.api.catalog.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/products/{productId}/media")
public class ProductMediaController {
    private final ProductMediaService media;

    public ProductMediaController(ProductMediaService media) { this.media = media; }

    @GetMapping
    public List<MediaResponse> list(@PathVariable UUID productId) {
        return media.findByProduct(productId).stream().map(item -> response(productId, item)).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> upload(@PathVariable UUID productId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "") String altText) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Choose an image file");
        if (file.getSize() > ProductMediaService.MAX_UPLOAD_BYTES) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "Image upload is limited to 10 MiB");
        MediaResponse result = response(productId, media.upload(productId, file.getBytes(), altText));
        return ResponseEntity.created(URI.create(result.contentUrl())).body(result);
    }

    @PutMapping(value = "/{mediaId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaResponse replace(@PathVariable UUID productId, @PathVariable UUID mediaId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "") String altText) throws java.io.IOException {
        if (file == null || file.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Choose an image file");
        if (file.getSize() > ProductMediaService.MAX_UPLOAD_BYTES) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, "Image upload is limited to 10 MiB");
        return response(productId, media.replace(productId, mediaId, file.getBytes(), altText));
    }

    @PutMapping("/order")
    public List<MediaResponse> reorder(@PathVariable UUID productId, @RequestBody OrderRequest request) {
        media.reorder(productId, request == null ? null : request.mediaIds());
        return list(productId);
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> delete(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        media.delete(productId, mediaId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{mediaId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        ProductMediaService.MediaContent image = media.content(productId, mediaId);
        String extension = "image/jpeg".equals(image.contentType()) ? "jpg" : "png";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("artio-product-image." + extension).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache().cachePublic())
                .body(image.bytes());
    }

    private static MediaResponse response(UUID productId, ProductMediaService.MediaView item) {
        return new MediaResponse(item.id(), item.productId(), item.contentType(), item.width(), item.height(),
                item.altText(), item.position(), item.createdAt(), item.updatedAt(), "/api/v1/products/" + productId + "/media/" + item.id() + "/content");
    }

    public record OrderRequest(List<UUID> mediaIds) { }
    public record MediaResponse(UUID id, UUID productId, String contentType, int width, int height,
                                String altText, int position, java.time.Instant createdAt, java.time.Instant updatedAt,
                                String contentUrl) { }
}
