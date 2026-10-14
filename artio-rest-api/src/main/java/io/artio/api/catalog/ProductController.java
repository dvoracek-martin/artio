package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductApplicationService products;

    public ProductController(ProductApplicationService products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductResponse> findAll() {
        return products.findAll();
    }

    @GetMapping("/search")
    public List<io.artio.catalog.ports.ProductSearchResult> search(
            @org.springframework.web.bind.annotation.RequestParam String q,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "en") String locale) {
        return products.search(q, limit, locale);
    }

    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable UUID id) {
        return products.findById(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestBody ProductRequest request) {
        var product = products.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<ProductResponse> duplicate(@PathVariable UUID id,@RequestBody ProductRequest request){
        var product=products.duplicate(id,request);
        return ResponseEntity.created(URI.create("/api/products/"+product.id())).body(product);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @RequestBody UpdateProductRequest request) {
        return products.update(id, request);
    }

    @PutMapping("/{id}/active")
    public ProductResponse setActive(@PathVariable UUID id, @RequestBody SetActiveRequest request) {
        return products.setActive(id, request.active());
    }

    @PutMapping("/{id}/archive")
    public ProductResponse archive(@PathVariable UUID id) { return products.archive(id); }

    @PutMapping("/{id}/restore")
    public ProductResponse restore(@PathVariable UUID id) { return products.restore(id); }

    @PutMapping("/{id}/publication-schedule")
    public ProductResponse schedulePublication(@PathVariable UUID id,
            @RequestBody PublicationScheduleRequest request) {
        return products.schedulePublication(id, request.publishAt());
    }

    @DeleteMapping("/{id}/publication-schedule")
    public ProductResponse cancelScheduledPublication(@PathVariable UUID id) {
        return products.cancelScheduledPublication(id);
    }

    @PutMapping("/{id}/catalog-metadata")
    public ProductResponse updateCatalogMetadata(@PathVariable UUID id,
            @RequestBody ProductApplicationService.CatalogMetadataRequest request) {
        return products.updateCatalogMetadata(id, request);
    }

    @PutMapping("/{id}/seo")
    public ProductResponse updateSeo(@PathVariable UUID id, @RequestBody ProductApplicationService.SeoMetadataRequest request) {
        return products.updateSeo(id, request);
    }

    public record PublicationScheduleRequest(java.time.Instant publishAt) { }
}
