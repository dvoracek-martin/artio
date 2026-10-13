package io.artio.catalog;

import io.artio.kernel.Aggregate;
import java.time.Instant;
import java.util.Objects;

/** A sellable catalog variation, modeled independently from its parent product. */
public final class Variant extends Aggregate<VariantId> {
    private final ProductId productId;
    private final VariantName name;
    private final Sku sku;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    private Variant(VariantId id, ProductId productId, VariantName name, Sku sku,
                    boolean active, Instant createdAt, Instant updatedAt) {
        super(id);
        this.productId = Objects.requireNonNull(productId, "Parent product ID is required");
        this.name = Objects.requireNonNull(name, "Variant name is required");
        this.sku = Objects.requireNonNull(sku, "SKU is required");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time is required");
    }

    public static Variant create(VariantId id, ProductId productId, VariantName name, Sku sku) {
        var now = Instant.now();
        var variant = new Variant(id, productId, name, sku, true, now, now);
        variant.recordEvent(new VariantCreated(id, productId, name, sku));
        return variant;
    }

    public static Variant reconstitute(VariantId id, ProductId productId, VariantName name, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt) {
        return new Variant(id, productId, name, sku, active, createdAt, updatedAt);
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
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}