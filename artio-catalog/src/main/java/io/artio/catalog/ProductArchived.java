package io.artio.catalog;

import java.time.Instant;

public record ProductArchived(ProductId productId, Instant archivedAt) implements ProductEvent { }
