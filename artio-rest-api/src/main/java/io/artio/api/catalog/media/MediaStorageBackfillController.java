package io.artio.api.catalog.media;

import io.artio.api.security.PrivilegedStepUpMfa;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, resumable administrator-controlled migration; the source store is never deleted. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
@RestController
@RequestMapping("/api/v1/admin/catalog/media/storage-backfill")
public class MediaStorageBackfillController {
    private final MediaStorageBackfillService service;
    private final PrivilegedStepUpMfa stepUpMfa;

    public MediaStorageBackfillController(MediaStorageBackfillService service, PrivilegedStepUpMfa stepUpMfa) {
        this.service = service;
        this.stepUpMfa = stepUpMfa;
    }

    @PostMapping
    public MediaStorageBackfillService.BackfillResult backfill(
            @RequestParam(required = false) String afterStorageKey,
            @RequestParam(defaultValue = "50") int limit, JwtAuthenticationToken auth) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(authority -> "ROLE_SUPERADMIN".equals(authority.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a superadmin can migrate plugin media storage");
        stepUpMfa.require(auth);
        return service.backfill(afterStorageKey, limit);
    }
}
