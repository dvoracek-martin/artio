package io.artio.catalog;
import java.util.Set;
public record ProductClassificationChanged(ProductId productId, BrandId brandId, Set<CategoryId> categoryIds) implements ProductEvent { public ProductClassificationChanged { categoryIds = Set.copyOf(categoryIds); } }
