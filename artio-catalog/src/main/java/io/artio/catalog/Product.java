package io.artio.catalog;

import io.artio.kernel.Aggregate;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.LinkedHashMap;

/** Catalog aggregate for merchandise identity and descriptive information. */
public final class Product extends Aggregate<ProductId> {
    private ProductName name;
    private ProductDescription description;
    private final Sku sku;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;
    private BrandId brandId;
    private final Set<CategoryId> categoryIds = new LinkedHashSet<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private ProductSeoMetadata seoMetadata;
    private Instant archivedAt;
    private Instant scheduledPublishAt;

    private Product(ProductId id, ProductName name, ProductDescription description, Sku sku,
                    boolean active, Instant createdAt, Instant updatedAt) {
        super(id);
        this.name = Objects.requireNonNull(name, "Product name is required");
        this.description = Objects.requireNonNull(description, "Product description value object is required");
        this.sku = Objects.requireNonNull(sku, "SKU is required");
        this.active = active;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time is required");
    }

    public static Product create(ProductId id, ProductName name, ProductDescription description, Sku sku) {
        var now = Instant.now();
        var product = new Product(id, name, description, sku, true, now, now);
        product.recordEvent(new ProductCreated(id, name, description, sku));
        return product;
    }

    /** Rebuilds a stored aggregate without creating a new domain event. */
    public static Product reconstitute(ProductId id, ProductName name, ProductDescription description, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt) {
        return new Product(id, name, description, sku, active, createdAt, updatedAt);
    }

    public static Product reconstitute(ProductId id, ProductName name, ProductDescription description, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt, BrandId brandId,
                                       Set<CategoryId> categoryIds, Map<String, String> attributes) {
        var product = reconstitute(id, name, description, sku, active, createdAt, updatedAt);
        product.brandId = brandId;
        product.categoryIds.addAll(categoryIds);
        product.attributes.putAll(attributes);
        return product;
    }

    public static Product reconstitute(ProductId id, ProductName name, ProductDescription description, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt, BrandId brandId,
                                       Set<CategoryId> categoryIds, Map<String, String> attributes, ProductSeoMetadata seoMetadata,
                                       Instant archivedAt) {
        var product = reconstitute(id, name, description, sku, active, createdAt, updatedAt, brandId, categoryIds, attributes, seoMetadata);
        product.archivedAt = archivedAt;
        return product;
    }

    public static Product reconstitute(ProductId id, ProductName name, ProductDescription description, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt, BrandId brandId,
                                       Set<CategoryId> categoryIds, Map<String, String> attributes, ProductSeoMetadata seoMetadata,
                                       Instant archivedAt, Instant scheduledPublishAt) {
        var product = reconstitute(id, name, description, sku, active, createdAt, updatedAt,
                brandId, categoryIds, attributes, seoMetadata, archivedAt);
        product.scheduledPublishAt = scheduledPublishAt;
        return product;
    }

    public void schedulePublication(Instant publishAt, Instant now) {
        Objects.requireNonNull(publishAt, "Publication time is required");
        Objects.requireNonNull(now, "Current time is required");
        if (!publishAt.isAfter(now)) throw new IllegalArgumentException("Publication time must be in the future");
        if (isArchived()) throw new IllegalStateException("Archived products cannot be scheduled for publication");
        if (active) throw new IllegalStateException("Active products cannot be scheduled for publication");
        if (publishAt.equals(scheduledPublishAt)) return;
        scheduledPublishAt = publishAt;
        updatedAt = now;
        recordEvent(new ProductPublicationScheduled(getId(), publishAt));
    }

    public void cancelScheduledPublication(Instant now) {
        if (scheduledPublishAt == null) throw new IllegalStateException("Product has no scheduled publication");
        scheduledPublishAt = null;
        updatedAt = Objects.requireNonNull(now, "Current time is required");
        recordEvent(new ProductPublicationCancelled(getId()));
    }

    public boolean publishIfDue(Instant now) {
        if (scheduledPublishAt == null || scheduledPublishAt.isAfter(now)) return false;
        if (isArchived() || active) {
            scheduledPublishAt = null;
            updatedAt = now;
            recordEvent(new ProductPublicationCancelled(getId()));
            return true;
        }
        scheduledPublishAt = null;
        active = true;
        updatedAt = now;
        recordEvent(new ProductPublished(getId(), now));
        return true;
    }

    public static Product reconstitute(ProductId id, ProductName name, ProductDescription description, Sku sku,
                                       boolean active, Instant createdAt, Instant updatedAt, BrandId brandId,
                                       Set<CategoryId> categoryIds, Map<String, String> attributes, ProductSeoMetadata seoMetadata) {
        var product = reconstitute(id, name, description, sku, active, createdAt, updatedAt, brandId, categoryIds, attributes);
        product.seoMetadata = seoMetadata;
        return product;
    }

    public void updateSeoMetadata(ProductSeoMetadata metadata) {
        Objects.requireNonNull(metadata, "SEO metadata is required");
        if (metadata.equals(seoMetadata)) return;
        var previous = seoMetadata;
        seoMetadata = metadata;
        updatedAt = Instant.now();
        recordEvent(new ProductSeoMetadataUpdated(getId(), previous, metadata));
    }

    public void classify(BrandId brand, Set<CategoryId> categories) {
        Objects.requireNonNull(categories, "Categories are required");
        if (Objects.equals(brandId, brand) && categoryIds.equals(categories)) return;
        brandId = brand;
        categoryIds.clear();
        categoryIds.addAll(categories);
        updatedAt = Instant.now();
        recordEvent(new ProductClassificationChanged(getId(), brandId, Set.copyOf(categoryIds)));
    }

    public void setAttribute(AttributeDefinition definition, String value) {
        Objects.requireNonNull(definition, "Attribute definition is required");
        if (!definition.isActive()) throw new IllegalArgumentException("Inactive attributes cannot be assigned");
        if (value == null) {
            if (definition.isRequired()) throw new IllegalArgumentException("Required attribute cannot be removed: " + definition.getCode().value());
            attributes.remove(definition.getCode().value());
        } else {
            switch (definition.getType()) {
                case TEXT -> { if (value.isBlank() || value.length() > 2000) throw new IllegalArgumentException("Text attribute must contain 1 to 2000 characters"); }
                case NUMBER -> { try { new java.math.BigDecimal(value); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Attribute must be a number: " + definition.getCode().value(), ex); } }
                case BOOLEAN -> { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Attribute must be true or false: " + definition.getCode().value()); value = value.toLowerCase(java.util.Locale.ROOT); }
            }
            attributes.put(definition.getCode().value(), value);
        }
        updatedAt = Instant.now();
        recordEvent(new ProductAttributeChanged(getId(), definition.getCode().value(), value));
    }

    public void activate() {
        if (isArchived()) throw new IllegalStateException("Archived product must be restored before activation");
        if (active) throw new IllegalStateException("Already active");
        cancelScheduleWhenPresent();
        active = true;
        updatedAt = Instant.now();
        recordEvent(new ProductActivated(getId()));
    }

    public void deactivate() {
        if (!active) throw new IllegalStateException("Already inactive");
        active = false;
        updatedAt = Instant.now();
        recordEvent(new ProductDeactivated(getId()));
    }

    public void archive() {
        if (isArchived()) throw new IllegalStateException("Already archived");
        cancelScheduleWhenPresent();
        active = false;
        archivedAt = Instant.now();
        updatedAt = archivedAt;
        recordEvent(new ProductArchived(getId(), archivedAt));
    }

    public void restoreFromArchive() {
        if (!isArchived()) throw new IllegalStateException("Product is not archived");
        archivedAt = null;
        active = false;
        updatedAt = Instant.now();
        recordEvent(new ProductRestored(getId()));
    }

    public void rename(ProductName newName) {
        Objects.requireNonNull(newName, "New product name is required");
        if (newName.equals(name)) return;
        var previousName = name;
        name = newName;
        updatedAt = Instant.now();
        recordEvent(new ProductRenamed(getId(), previousName, newName));
    }

    public void updateDescription(ProductDescription newDescription) {
        Objects.requireNonNull(newDescription, "New product description is required");
        if (newDescription.equals(description)) return;
        var previousDescription = description;
        description = newDescription;
        updatedAt = Instant.now();
        recordEvent(new ProductDescriptionUpdated(getId(), previousDescription, newDescription));
    }

    public ProductName getName() { return name; }
    public ProductDescription getDescription() { return description; }
    public Sku getSku() { return sku; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public BrandId getBrandId() { return brandId; }
    public Set<CategoryId> getCategoryIds() { return Set.copyOf(categoryIds); }
    public Map<String, String> getAttributes() { return Map.copyOf(attributes); }
    public ProductSeoMetadata getSeoMetadata() { return seoMetadata; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getScheduledPublishAt() { return scheduledPublishAt; }
    public boolean isArchived() { return archivedAt != null; }

    private void cancelScheduleWhenPresent() {
        if (scheduledPublishAt != null) {
            scheduledPublishAt = null;
            recordEvent(new ProductPublicationCancelled(getId()));
        }
    }
}
