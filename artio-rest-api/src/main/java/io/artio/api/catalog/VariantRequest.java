package io.artio.api.catalog;

import java.util.Map;

public record VariantRequest(String name, String sku, Map<String, String> attributes) {
    public VariantRequest(String name, String sku) { this(name, sku, Map.of()); }
}
