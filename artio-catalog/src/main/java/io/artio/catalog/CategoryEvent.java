package io.artio.catalog;

import io.artio.kernel.AggregateEvent;
public sealed interface CategoryEvent extends AggregateEvent permits CategoryCreated, CategoryRenamed, CategoryMoved, CategoryActiveChanged { CategoryId categoryId(); }
