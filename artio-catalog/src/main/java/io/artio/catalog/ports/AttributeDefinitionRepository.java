package io.artio.catalog.ports;
import io.artio.catalog.AttributeDefinition; import io.artio.catalog.AttributeDefinitionId; import java.util.List; import java.util.Optional;
public interface AttributeDefinitionRepository { Optional<AttributeDefinition> findById(AttributeDefinitionId id); List<AttributeDefinition> findAll(); void save(AttributeDefinition definition); }
