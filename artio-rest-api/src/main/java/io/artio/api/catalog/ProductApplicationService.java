package io.artio.api.catalog;

import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.ProductSearch;
import io.artio.catalog.ports.ProductSearchResult;
import io.artio.catalog.ports.BrandRepository;
import io.artio.catalog.ports.CategoryRepository;
import io.artio.catalog.ports.AttributeDefinitionRepository;
import io.artio.catalog.CategoryId;
import io.artio.catalog.BrandId;
import io.artio.catalog.AttributeDefinitionId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.beans.factory.ObjectProvider;

@Service
@Transactional
public class ProductApplicationService {
    private final ProductRepository products;
    private final ObjectProvider<ProductSearch> search;
    private final BrandRepository brands;
    private final CategoryRepository categories;
    private final AttributeDefinitionRepository attributeDefinitions;

    public ProductApplicationService(ProductRepository products, ObjectProvider<ProductSearch> search,
                                     BrandRepository brands, CategoryRepository categories,
                                     AttributeDefinitionRepository attributeDefinitions) {
        this.products = products;
        this.search = search;
        this.brands = brands;
        this.categories = categories;
        this.attributeDefinitions = attributeDefinitions;
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse create(ProductRequest request) {
        var product = Product.create(new ProductId(UUID.randomUUID()), new ProductName(request.name()),
                new ProductDescription(request.description()), new Sku(request.sku()));
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-products", key = "'all'")
    public List<ProductResponse> findAll() {
        return products.findAll().stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required");
        ProductSearch productSearch = search.getIfAvailable();
        if (productSearch == null) throw new IllegalStateException("Product search is not enabled");
        return productSearch.search(query.trim(), limit);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-products", key = "#id")
    public ProductResponse findById(UUID id) {
        return ProductResponse.from(findProduct(id));
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        var product = findProduct(id);
        product.rename(new ProductName(request.name()));
        product.updateDescription(new ProductDescription(request.description()));
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse setActive(UUID id, boolean active) {
        var product = findProduct(id);
        if (active != product.isActive()) {
            if (active) product.activate();
            else product.deactivate();
            products.save(product);
            index(product);
        }
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse updateCatalogMetadata(UUID id, CatalogMetadataRequest request) {
        var product = findProduct(id);
        BrandId brandId = request.brandId() == null ? null : new BrandId(request.brandId());
        if (brandId != null && brands.findById(brandId).isEmpty()) throw new CatalogNotFoundException("Brand not found: " + brandId.value());
        var categoryIds = request.categoryIds().stream().map(CategoryId::new).collect(java.util.stream.Collectors.toSet());
        for (CategoryId categoryId : categoryIds) {
            var category = categories.findById(categoryId).orElseThrow(() -> new CatalogNotFoundException("Category not found: " + categoryId.value()));
            if (!category.isActive()) throw new IllegalArgumentException("Inactive categories cannot be assigned");
        }
        product.classify(brandId, categoryIds);
        var values = request.attributes() == null ? java.util.Map.<String, String>of() : request.attributes();
        product.getAttributes().keySet().stream().filter(code -> !values.containsKey(code)).toList().forEach(code -> {
            var definition = attributeDefinitions.findAll().stream().filter(candidate -> candidate.getCode().value().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Stored attribute definition is missing: " + code));
            product.setAttribute(definition, null);
        });
        values.forEach((code, value) -> {
            var definition = attributeDefinitions.findAll().stream().filter(candidate -> candidate.getCode().value().equals(code)).findFirst()
                    .orElseThrow(() -> new CatalogNotFoundException("Attribute definition not found: " + code));
            product.setAttribute(definition, value);
        });
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    public record CatalogMetadataRequest(UUID brandId, java.util.Set<UUID> categoryIds,
                                         java.util.Map<String, String> attributes) {
        public CatalogMetadataRequest {
            categoryIds = categoryIds == null ? java.util.Set.of() : java.util.Set.copyOf(categoryIds);
        }
    }

    private Product findProduct(UUID id) {
        return products.findById(new ProductId(id))
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + id));
    }

    private void index(Product product) {
        ProductSearch productSearch = search.getIfAvailable();
        if (productSearch != null) productSearch.index(product);
    }
}
