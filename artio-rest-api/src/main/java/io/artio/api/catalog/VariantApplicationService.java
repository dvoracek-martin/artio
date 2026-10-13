package io.artio.api.catalog;

import io.artio.catalog.ProductId;
import io.artio.catalog.Sku;
import io.artio.catalog.Variant;
import io.artio.catalog.VariantId;
import io.artio.catalog.VariantName;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.VariantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VariantApplicationService {
    private final ProductRepository products;
    private final VariantRepository variants;

    public VariantApplicationService(ProductRepository products, VariantRepository variants) {
        this.products = products;
        this.variants = variants;
    }

    public VariantResponse create(UUID productId, VariantRequest request) {
        var parentId = new ProductId(productId);
        if (products.findById(parentId).isEmpty()) {
            throw new CatalogNotFoundException("Product not found: " + productId);
        }
        var variant = Variant.create(new VariantId(UUID.randomUUID()), parentId,
                new VariantName(request.name()), new Sku(request.sku()));
        variants.save(variant);
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
            if (active) variant.activate();
            else variant.deactivate();
            variants.save(variant);
        }
        return VariantResponse.from(variant);
    }

    private Variant findVariant(UUID id) {
        return variants.findById(new VariantId(id))
                .orElseThrow(() -> new CatalogNotFoundException("Variant not found: " + id));
    }
}