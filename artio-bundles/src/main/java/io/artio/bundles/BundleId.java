package io.artio.bundles;
import io.artio.kernel.ValueObject; import java.util.Objects; import java.util.UUID;
public record BundleId(UUID value) implements ValueObject { public BundleId { Objects.requireNonNull(value,"Bundle ID is required"); } }
