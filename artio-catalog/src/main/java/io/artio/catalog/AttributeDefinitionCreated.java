package io.artio.catalog;
public record AttributeDefinitionCreated(AttributeDefinitionId definitionId, AttributeCode code, AttributeDefinition.Type type) implements AttributeDefinitionEvent {}
