package io.artio.api.licensing;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/admin/system/license")
public final class LicenseController {
    private final LicensingService licensing;
    public LicenseController(LicensingService licensing) { this.licensing = licensing; }

    @GetMapping
    public LicensingService.Status status(JwtAuthenticationToken actor) {
        requireOwner(actor);
        return licensing.status();
    }

    @PostMapping
    public LicensingService.Status install(HttpServletRequest request, JwtAuthenticationToken actor) throws IOException {
        requireOwner(actor);
        if (request.getContentLengthLong() > SignedEntitlementVerifier.MAX_DOCUMENT_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Signed entitlement exceeds 64 KiB");
        byte[] document = request.getInputStream().readNBytes(SignedEntitlementVerifier.MAX_DOCUMENT_BYTES + 1);
        if (document.length > SignedEntitlementVerifier.MAX_DOCUMENT_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Signed entitlement exceeds 64 KiB");
        return licensing.install(document, UUID.fromString(actor.getToken().getSubject()));
    }

    @PostMapping("/refresh")
    public LicensingService.Status refresh(JwtAuthenticationToken actor) {
        requireOwner(actor);
        return licensing.refresh(UUID.fromString(actor.getToken().getSubject()));
    }

    private static void requireOwner(JwtAuthenticationToken actor) {
        if (actor == null || !"SUPERADMIN".equals(actor.getToken().getClaimAsString("role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the system owner can manage entitlements");
    }
}
