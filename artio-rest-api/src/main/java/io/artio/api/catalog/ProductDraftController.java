package io.artio.api.catalog;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Creates the wizard's product, classification and first variant as one transaction. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/products/drafts")
public class ProductDraftController {
    private final ProductApplicationService products;
    private final VariantApplicationService variants;

    public ProductDraftController(ProductApplicationService products, VariantApplicationService variants) {
        this.products = products;
        this.variants = variants;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ProductResponse> create(@RequestBody DraftRequest request) {
        if (request.categoryIds() == null || request.categoryIds().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one product category");
        }
        var product = products.create(new ProductRequest(request.name(), request.description(), request.sku()));
        products.setActive(product.id(), false);
        variants.create(product.id(), new VariantRequest(request.variantName(), request.variantSku(), Map.of()));
        var classified = products.updateCatalogMetadata(product.id(),
                new ProductApplicationService.CatalogMetadataRequest(request.brandId(), request.categoryIds(), request.attributes()));
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id())).body(classified);
    }

    public record DraftRequest(String name, String description, String sku, String variantName, String variantSku,
                               UUID brandId, Set<UUID> categoryIds, Map<String, String> attributes) { }
}
