package io.artio.api.licensing;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the last verified grant; transport failures cannot extend or erase its signed validity window. */
public final class EntitlementPolicy {
    private final SignedEntitlementVerifier verifier;
    private final AtomicReference<VerifiedEntitlement> current = new AtomicReference<>();

    public EntitlementPolicy(SignedEntitlementVerifier verifier) {
        this.verifier = verifier;
    }

    public synchronized VerifiedEntitlement accept(byte[] signedDocument, Instant now) {
        VerifiedEntitlement next = validateReplacement(signedDocument, now);
        current.set(next);
        return next;
    }

    public synchronized VerifiedEntitlement validateReplacement(byte[] signedDocument, Instant now) {
        VerifiedEntitlement next = verifier.verify(signedDocument, now);
        if (next.stateAt(now) == VerifiedEntitlement.State.NOT_YET_VALID)
            throw new IllegalArgumentException("Entitlement is not yet applicable");
        VerifiedEntitlement previous = current.get();
        if (previous != null && (next.issuedAt().isBefore(previous.issuedAt())
                || (next.issuedAt().equals(previous.issuedAt()) && !next.equals(previous))))
            throw new IllegalArgumentException("Entitlement replacement is older or conflicts with the current grant");
        return next;
    }

    public Optional<VerifiedEntitlement> current() {
        return Optional.ofNullable(current.get());
    }

    public boolean allows(EnterpriseFeature feature, Instant now) {
        VerifiedEntitlement entitlement = current.get();
        return entitlement != null && entitlement.allows(feature, now);
    }

    public void require(EnterpriseFeature feature, Instant now) {
        if (!allows(feature, now)) throw new FeatureNotLicensedException(feature);
    }
}
