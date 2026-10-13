package io.artio.catalog;
public record CategoryActiveChanged(CategoryId categoryId, boolean active) implements CategoryEvent {}
