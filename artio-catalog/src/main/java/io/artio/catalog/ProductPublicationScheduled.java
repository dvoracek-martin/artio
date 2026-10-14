package io.artio.catalog;

import java.time.Instant;

public record ProductPublicationScheduled(ProductId productId, Instant publishAt) implements ProductEvent { }
