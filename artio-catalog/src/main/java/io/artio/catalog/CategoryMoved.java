package io.artio.catalog;
public record CategoryMoved(CategoryId categoryId, CategoryId oldParentId, CategoryId newParentId) implements CategoryEvent {}
