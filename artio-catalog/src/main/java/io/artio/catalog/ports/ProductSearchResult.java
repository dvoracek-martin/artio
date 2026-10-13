package io.artio.catalog.ports;
import java.util.UUID;
public record ProductSearchResult(UUID id, String name, String description, String sku, boolean active) {}
