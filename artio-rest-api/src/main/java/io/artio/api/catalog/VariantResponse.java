package io.artio.api.catalog;

import io.artio.catalog.Variant;
import java.time.Instant;
import java.util.UUID;

public record VariantResponse(UUID id, UUID productId, String name, String sku,
                              boolean active, Instant createdAt, Instant updatedAt) {
    public static VariantResponse from(Variant variant) {
        return new VariantResponse(variant.getId().value(), variant.getProductId().value(),
                variant.getName().value(), variant.getSku().value(), variant.isActive(),
                variant.getCreatedAt(), variant.getUpdatedAt());
    }
}