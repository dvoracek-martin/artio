package io.artio.api.catalog;

import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import io.artio.catalog.Variant;
import io.artio.catalog.VariantId;
import io.artio.catalog.VariantName;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.VariantRepository;
import io.artio.catalog.ports.AttributeDefinitionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VariantApplicationService {
    private final ProductRepository products;
    private final VariantRepository variants;
    private final AttributeDefinitionRepository attributeDefinitions;

    public VariantApplicationService(ProductRepository products, VariantRepository variants,
                                     AttributeDefinitionRepository attributeDefinitions) {
        this.products = products;
        this.variants = variants;
        this.attributeDefinitions = attributeDefinitions;
    }

    public VariantResponse create(UUID productId, VariantRequest request) {
        var parentId = new ProductId(productId);
        var parent=products.findById(parentId).orElseThrow(() -> new CatalogNotFoundException("Product not found: " + productId));
        if(parent.isArchived())throw new IllegalStateException("Archived product cannot receive new variants");
        Map<String, String> attributes = validateAttributes(request.attributes());
        ensureUniqueCombination(parentId, attributes, null);
        var variant = Variant.create(new VariantId(UUID.randomUUID()), parentId,
                new VariantName(request.name()), new Sku(request.sku()), attributes);
        saveVariant(variant);
        return VariantResponse.from(variant);
    }

    @Transactional(readOnly = true)
    public List<VariantResponse> findByProductId(UUID productId) {
        var parentId = new ProductId(productId);
        if (products.findById(parentId).isEmpty()) {
            throw new CatalogNotFoundException("Product not found: " + productId);
        }
        return variants.findByProductId(parentId).stream().map(VariantResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public VariantResponse findById(UUID id) {
        return VariantResponse.from(findVariant(id));
    }

    public VariantResponse setActive(UUID id, boolean active) {
        var variant = findVariant(id);
        if (active != variant.isActive()) {
            if(active&&products.findById(variant.getProductId()).map(io.artio.catalog.Product::isArchived).orElse(false))
                throw new IllegalStateException("Archived product variants cannot be activated");
            if (active) variant.activate();
            else variant.deactivate();
            variants.save(variant);
        }
        return VariantResponse.from(variant);
    }

    public VariantResponse update(UUID id, VariantRequest request) {
        if (request == null) throw new IllegalArgumentException("Variant name, SKU and option values are required");
        var variant = findVariant(id);
        var parent = products.findById(variant.getProductId())
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + variant.getProductId().value()));
        if (parent.isArchived()) throw new IllegalStateException("Archived products cannot have their variants changed");
        Map<String, String> attributes = validateAttributes(request.attributes());
        ensureUniqueCombination(variant.getProductId(), attributes, id);
        variant.update(new VariantName(request.name()), new Sku(request.sku()), attributes);
        saveVariant(variant);
        return VariantResponse.from(variant);
    }

    private Map<String, String> validateAttributes(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) return Map.of();
        var definitions = attributeDefinitions.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(definition -> definition.getCode().value(), definition -> definition));
        var normalized = new java.util.TreeMap<String, String>();
        requested.forEach((code, raw) -> {
            var definition = definitions.get(code);
            if (definition == null) throw new CatalogNotFoundException("Variant option definition not found: " + code);
            if (!definition.isActive()) throw new IllegalArgumentException("Inactive attributes cannot be assigned to a variant: " + code);
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Variant option value is required: " + code);
            String value = raw.trim();
            switch (definition.getType()) {
                case TEXT -> { if (value.length() > 2000) throw new IllegalArgumentException("Text option cannot exceed 2000 characters: " + code); }
                case NUMBER -> {
                    try {
                        value = new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
                    } catch (NumberFormatException invalid) {
                        throw new IllegalArgumentException("Variant option must be a number: " + code, invalid);
                    }
                }
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Variant option must be true or false: " + code);
                    value = value.toLowerCase(java.util.Locale.ROOT);
                }
            }
            normalized.put(code, value);
        });
        return Map.copyOf(normalized);
    }

    private void ensureUniqueCombination(ProductId productId, Map<String, String> attributes, UUID exceptId) {
        if (attributes.isEmpty()) return;
        boolean duplicate = variants.findByProductId(productId).stream()
                .anyMatch(existing -> (exceptId == null || !existing.getId().value().equals(exceptId))
                        && existing.getAttributes().equals(attributes));
        if (duplicate) throw new IllegalStateException("A variant with the same option combination already exists");
    }

    private void saveVariant(Variant variant) {
        try {
            variants.save(variant);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Variant SKU or option combination already exists");
        }
    }

    private Variant findVariant(UUID id) {
        return variants.findById(new VariantId(id))
                .orElseThrow(() -> new CatalogNotFoundException("Variant not found: " + id));
    }
}
