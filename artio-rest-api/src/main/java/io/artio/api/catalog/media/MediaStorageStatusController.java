package io.artio.api.catalog.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/catalog/media/storage-status")
public class MediaStorageStatusController {
    private final ProductMediaService media;

    public MediaStorageStatusController(ProductMediaService media) {
        this.media = media;
    }

    @GetMapping
    public StorageStatus getStatus() {
        return new StorageStatus(media.isUploadAvailable(), "storage");
    }

    public record StorageStatus(boolean uploadAvailable, String installCategory) { }
}
