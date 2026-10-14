package io.artio.catalog;

import io.artio.kernel.Aggregate;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** A sellable catalog variation, modeled independently from its parent product. */
public final class Variant extends Aggregate<VariantId> {
    private final ProductId productId;
    private VariantName name;
    private Sku sku;
    private Map<String, String> attributes;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    private Variant(VariantId id, ProductId productId, VariantName name, Sku sku,
                    Map<String, String> attributes, boolean active, Instant createdAt, Instant updatedAt) {
        super(id);
        this.productId = Objects.requireNonNull(productId, "Parent product ID is required");
        this.name = Objects.requireNonNull(name, "Variant name is required");
        this.sku = Objects.requireNonNull(sku, "SKU is required");
        this.attributes = validAttributes(attributes);
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time is required");
    }

    public static Variant create(VariantId id, ProductId productId, VariantName name, Sku sku) {
        return create(id, productId, name, sku, Map.of());
    }

    public static Variant create(VariantId id, ProductId productId, VariantName name, Sku sku,
                                 Map<String, String> attributes) {
        var now = Instant.now();
        var variant = new Variant(id, productId, name, sku, attributes, true, now, now);
        variant.recordEvent(new VariantCreated(id, productId, name, sku, variant.attributes));
        return variant;
    }

    public static Variant reconstitute(VariantId id, ProductId productId, VariantName name, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt) {
        return reconstitute(id, productId, name, sku, Map.of(), active, createdAt, updatedAt);
    }

    public static Variant reconstitute(VariantId id, ProductId productId, VariantName name, Sku sku,
                                       Map<String, String> attributes, boolean active, Instant createdAt, Instant updatedAt) {
        return new Variant(id, productId, name, sku, attributes, active, createdAt, updatedAt);
    }

    public void update(VariantName name, Sku sku, Map<String, String> attributes) {
        VariantName nextName = Objects.requireNonNull(name, "Variant name is required");
        Sku nextSku = Objects.requireNonNull(sku, "SKU is required");
        Map<String, String> nextAttributes = validAttributes(attributes);
        if (!this.name.equals(nextName) || !this.sku.equals(nextSku) || !this.attributes.equals(nextAttributes)) {
            this.name = nextName;
            this.sku = nextSku;
            this.attributes = nextAttributes;
            updatedAt = Instant.now();
            recordEvent(new VariantDetailsChanged(getId(), nextName, nextSku, nextAttributes));
        }
    }

    public void activate() {
        if (active) {
            throw new IllegalStateException("Already active");
        }
        active = true;
        updatedAt = Instant.now();
        recordEvent(new VariantActivated(getId()));
    }

    public void deactivate() {
        if (!active) {
            throw new IllegalStateException("Already inactive");
        }
        active = false;
        updatedAt = Instant.now();
        recordEvent(new VariantDeactivated(getId()));
    }

    public ProductId getProductId() { return productId; }
    public VariantName getName() { return name; }
    public Sku getSku() { return sku; }
    public Map<String, String> getAttributes() { return attributes; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static Map<String, String> validAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return Map.of();
        var normalized = new java.util.TreeMap<String, String>();
        attributes.forEach((code, value) -> {
            if (code == null || !code.matches("[a-z][a-z0-9_]{1,63}")) throw new IllegalArgumentException("Variant attribute code is invalid");
            if (value == null || value.isBlank() || value.length() > 2000) throw new IllegalArgumentException("Variant attribute values must contain 1 to 2000 characters");
            normalized.put(code, value.trim());
        });
        if (normalized.size() > 50) throw new IllegalArgumentException("A variant cannot have more than 50 option values");
        return java.util.Collections.unmodifiableMap(normalized);
    }
}
