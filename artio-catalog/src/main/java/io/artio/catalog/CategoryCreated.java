package io.artio.catalog;

import java.util.Objects;
public record CategoryCreated(CategoryId categoryId, CategoryName name, CategoryId parentId) implements CategoryEvent {
    public CategoryCreated { Objects.requireNonNull(categoryId); Objects.requireNonNull(name); }
}
