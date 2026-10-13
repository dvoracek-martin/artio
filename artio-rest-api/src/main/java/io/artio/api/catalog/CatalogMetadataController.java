package io.artio.api.catalog;
import java.net.URI; import java.util.List; import java.util.UUID;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api")
public class CatalogMetadataController {
    private final MetadataApplicationService metadata;
    public CatalogMetadataController(MetadataApplicationService metadata) { this.metadata = metadata; }
    @GetMapping("/categories") public List<MetadataApplicationService.CategoryView> categories() { return metadata.categories(); }
    @PostMapping("/categories") public ResponseEntity<MetadataApplicationService.CategoryView> createCategory(@RequestBody MetadataApplicationService.CategoryRequest request) { var value = metadata.createCategory(request); return ResponseEntity.created(URI.create("/api/categories/" + value.id())).body(value); }
    @PutMapping("/categories/{id}") public MetadataApplicationService.CategoryView updateCategory(@PathVariable UUID id, @RequestBody MetadataApplicationService.CategoryRequest request) { return metadata.updateCategory(id, request); }
    @GetMapping("/brands") public List<MetadataApplicationService.BrandView> brands() { return metadata.brands(); }
    @PostMapping("/brands") public ResponseEntity<MetadataApplicationService.BrandView> createBrand(@RequestBody MetadataApplicationService.BrandRequest request) { var value = metadata.createBrand(request); return ResponseEntity.created(URI.create("/api/brands/" + value.id())).body(value); }
    @PutMapping("/brands/{id}") public MetadataApplicationService.BrandView updateBrand(@PathVariable UUID id, @RequestBody MetadataApplicationService.BrandRequest request) { return metadata.updateBrand(id, request); }
    @GetMapping("/attribute-definitions") public List<MetadataApplicationService.AttributeView> attributes() { return metadata.attributes(); }
    @PostMapping("/attribute-definitions") public ResponseEntity<MetadataApplicationService.AttributeView> createAttribute(@RequestBody MetadataApplicationService.AttributeRequest request) { var value = metadata.createAttribute(request); return ResponseEntity.created(URI.create("/api/attribute-definitions/" + value.id())).body(value); }
    @PutMapping("/attribute-definitions/{id}") public MetadataApplicationService.AttributeView updateAttribute(@PathVariable UUID id, @RequestBody MetadataApplicationService.AttributeUpdateRequest request) { return metadata.updateAttribute(id, request); }
}
