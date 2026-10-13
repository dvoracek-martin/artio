package io.artio.catalog;
public record CategoryRenamed(CategoryId categoryId, CategoryName oldName, CategoryName newName) implements CategoryEvent {}
