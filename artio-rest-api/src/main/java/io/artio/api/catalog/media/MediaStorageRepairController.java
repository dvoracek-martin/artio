package io.artio.api.catalog.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, resumable repair of plugin-backed media placements. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.catalog.media.storage", havingValue = "plugins")
@RestController
@RequestMapping("/api/v1/admin/catalog/media/storage-repair")
public class MediaStorageRepairController {
    private final MediaStorageRepairService service;

    public MediaStorageRepairController(MediaStorageRepairService service) {
        this.service = service;
    }

    @PostMapping
    public MediaStorageRepairService.RepairResult repair(
            @RequestParam(required = false) String afterStorageKey,
            @RequestParam(defaultValue = "50") int limit, @RequestParam UUID repairRunId, JwtAuthenticationToken auth) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(authority -> "ROLE_SUPERADMIN".equals(authority.getAuthority())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a superadmin can repair plugin media storage");
        return service.repair(afterStorageKey, limit, repairRunId);
    }
}
