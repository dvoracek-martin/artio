package io.artio.catalog;
public record AttributeDefinitionActiveChanged(AttributeDefinitionId definitionId, boolean active) implements AttributeDefinitionEvent {}
