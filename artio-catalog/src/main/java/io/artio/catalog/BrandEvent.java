package io.artio.catalog;
import io.artio.kernel.AggregateEvent;
public sealed interface BrandEvent extends AggregateEvent permits BrandCreated, BrandUpdated, BrandActiveChanged { BrandId brandId(); }
