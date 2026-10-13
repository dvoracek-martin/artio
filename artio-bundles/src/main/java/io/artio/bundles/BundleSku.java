package io.artio.bundles;
import io.artio.kernel.ValueObject;
public record BundleSku(String value) implements ValueObject { public BundleSku { if(value==null||!value.matches("[A-Z0-9-]{3,50}"))throw new IllegalArgumentException("Bundle SKU must use 3-50 uppercase letters, digits, or hyphens"); } }
