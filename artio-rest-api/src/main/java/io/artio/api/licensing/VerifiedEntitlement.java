package io.artio.api.licensing;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Created by local verification. API status must not serialize this issuer-bearing internal record. */
public record VerifiedEntitlement(UUID licenseId, UUID installationId, Instant issuedAt, Instant notBefore,
        Instant expiresAt, Instant graceUntil, Set<EnterpriseFeature> features) {
    public VerifiedEntitlement {
        features = Set.copyOf(features);
    }

    public enum State { NOT_YET_VALID, ACTIVE, GRACE, EXPIRED }

    public State stateAt(Instant now) {
        if (now.isBefore(notBefore)) return State.NOT_YET_VALID;
        if (now.isBefore(expiresAt)) return State.ACTIVE;
        if (now.isBefore(graceUntil)) return State.GRACE;
        return State.EXPIRED;
    }

    public boolean allows(EnterpriseFeature feature, Instant now) {
        State state = stateAt(now);
        return (state == State.ACTIVE || state == State.GRACE) && features.contains(feature);
    }
}
