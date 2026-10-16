package io.artio.api.licensing;

import java.time.Instant;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe edition information for staff workflows; installation and issuer identifiers stay owner-only. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/license")
public final class FeatureAvailabilityController {
    private final LicensingService licensing;

    public FeatureAvailabilityController(LicensingService licensing) { this.licensing = licensing; }

    @GetMapping("/availability")
    public Availability availability() {
        LicensingService.Status status = licensing.status();
        return new Availability(status.edition(), status.state(), status.features(), status.expiresAt(), status.graceUntil());
    }

    public record Availability(String edition, String state, Set<EnterpriseFeature> features,
            Instant expiresAt, Instant graceUntil) { }
}
