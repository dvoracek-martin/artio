package io.artio.catalog;

import java.time.Instant;

public record ProductPublished(ProductId productId, Instant publishedAt) implements ProductEvent { }
