package io.artio.api.catalog.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit, resumable administrator-controlled migration; the source store is never deleted. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
@RestController
@RequestMapping("/api/v1/admin/catalog/media/storage-backfill")
public class MediaStorageBackfillController {
    private final MediaStorageBackfillService service;

    public MediaStorageBackfillController(MediaStorageBackfillService service) {
        this.service = service;
    }

    @PostMapping
    public MediaStorageBackfillService.BackfillResult backfill(
            @RequestParam(required = false) String afterStorageKey,
            @RequestParam(defaultValue = "50") int limit) {
        return service.backfill(afterStorageKey, limit);
    }
}
