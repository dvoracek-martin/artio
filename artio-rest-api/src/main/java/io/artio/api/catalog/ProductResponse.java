package io.artio.api.catalog;

import io.artio.catalog.Product;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import java.util.Map;

public record ProductResponse(UUID id, String name, String description, String sku,
                              boolean active, Instant createdAt, Instant updatedAt,
                              UUID brandId, Set<UUID> categoryIds, Map<String, String> attributes) implements java.io.Serializable {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId().value(), product.getName().value(),
                product.getDescription().value(), product.getSku().value(), product.isActive(),
                product.getCreatedAt(), product.getUpdatedAt(),
                product.getBrandId() == null ? null : product.getBrandId().value(),
                product.getCategoryIds().stream().map(id -> id.value()).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                product.getAttributes());
    }
}
