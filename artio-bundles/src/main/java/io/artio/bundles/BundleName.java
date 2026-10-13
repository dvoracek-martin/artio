package io.artio.bundles;
import io.artio.kernel.ValueObject;
public record BundleName(String value) implements ValueObject { public BundleName { if(value==null||value.isBlank())throw new IllegalArgumentException("Bundle name is required");value=value.trim();if(value.length()>500)throw new IllegalArgumentException("Bundle name cannot exceed 500 characters"); } }
