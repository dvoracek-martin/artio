package io.artio.api.licensing;

public final class FeatureNotLicensedException extends RuntimeException {
    private final EnterpriseFeature feature;

    public FeatureNotLicensedException(EnterpriseFeature feature) {
        super("Enterprise entitlement is unavailable for this operation");
        this.feature = java.util.Objects.requireNonNull(feature);
    }

    public EnterpriseFeature feature() { return feature; }
}
