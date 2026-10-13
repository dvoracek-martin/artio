package io.artio.catalog;
import io.artio.kernel.AggregateEvent;
public sealed interface AttributeDefinitionEvent extends AggregateEvent permits AttributeDefinitionCreated, AttributeDefinitionRenamed, AttributeDefinitionActiveChanged { AttributeDefinitionId definitionId(); }
