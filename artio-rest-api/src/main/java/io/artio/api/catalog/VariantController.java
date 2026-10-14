package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/products/{productId}/variants")
public class VariantController {
    private final VariantApplicationService variants;

    public VariantController(VariantApplicationService variants) {
        this.variants = variants;
    }

    @GetMapping
    public List<VariantResponse> findByProductId(@PathVariable UUID productId) {
        return variants.findByProductId(productId);
    }

    @PostMapping
    public ResponseEntity<VariantResponse> create(@PathVariable UUID productId,
                                                   @RequestBody VariantRequest request) {
        var variant = variants.create(productId, request);
        return ResponseEntity.created(URI.create("/api/products/" + productId + "/variants/" + variant.id()))
                .body(variant);
    }
}