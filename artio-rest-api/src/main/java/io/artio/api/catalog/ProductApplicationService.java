package io.artio.api.catalog;

import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.LocaleCode;
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
    private final CatalogSearchIndexLock searchIndexLock;

    public ProductApplicationService(ProductRepository products, ObjectProvider<ProductSearch> search,
                                     BrandRepository brands, CategoryRepository categories,
                                     AttributeDefinitionRepository attributeDefinitions,
                                     CatalogSearchIndexLock searchIndexLock) {
        this.products = products;
        this.search = search;
        this.brands = brands;
        this.categories = categories;
        this.attributeDefinitions = attributeDefinitions;
        this.searchIndexLock = searchIndexLock;
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse create(ProductRequest request) {
        searchIndexLock.lockCatalogWrite();
        var product = Product.create(new ProductId(UUID.randomUUID()), new ProductName(request.name()),
                new ProductDescription(request.description()), new Sku(request.sku()));
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse duplicate(UUID sourceId,ProductRequest request){
        if(request==null)throw new IllegalArgumentException("A new product name and SKU are required");
        searchIndexLock.lockCatalogWrite();
        Product source=findProduct(sourceId);
        Product copy=Product.create(new ProductId(UUID.randomUUID()),new ProductName(request.name()),
                new ProductDescription(request.description()),new Sku(request.sku()));
        copy.deactivate();
        copy.classify(source.getBrandId(),source.getCategoryIds());
        var definitions=attributeDefinitions.findAll().stream().collect(java.util.stream.Collectors.toMap(
                definition->definition.getCode().value(),definition->definition));
        source.getAttributes().forEach((code,value)->{
            var definition=definitions.get(code);
            if(definition==null)throw new IllegalStateException("Stored attribute definition is missing: "+code);
            copy.setAttribute(definition,value);
        });
        products.save(copy);
        index(copy);
        return ProductResponse.from(copy);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-products", key = "'all'")
    public List<ProductResponse> findAll() {
        return products.findAll().stream().map(ProductResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ProductSearchResult> search(String query, int limit) {
        return search(query, limit, "en");
    }

    @Transactional(readOnly = true)
    public List<ProductSearchResult> search(String query, int limit, String locale) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required");
        LocaleCode localeCode = new LocaleCode(locale);
        ProductSearch productSearch = search.getIfAvailable();
        if (productSearch == null) throw new IllegalStateException("Product search is not enabled");
        return productSearch.search(query.trim(), limit, localeCode);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "catalog-products", key = "#id")
    public ProductResponse findById(UUID id) {
        return ProductResponse.from(findProduct(id));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ProductResponse> findBySku(String sku) {
        return products.findBySku(new Sku(sku)).map(ProductResponse::from);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        searchIndexLock.lockCatalogWrite();
        var product = findProduct(id);
        product.rename(new ProductName(request.name()));
        product.updateDescription(new ProductDescription(request.description()));
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse setActive(UUID id, boolean active) {
        searchIndexLock.lockCatalogWrite();
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
    public ProductResponse schedulePublication(UUID id, java.time.Instant publishAt) {
        searchIndexLock.lockCatalogWrite();
        var product = findProduct(id);
        product.schedulePublication(publishAt, java.time.Instant.now());
        products.save(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse cancelScheduledPublication(UUID id) {
        searchIndexLock.lockCatalogWrite();
        var product = findProduct(id);
        product.cancelScheduledPublication(java.time.Instant.now());
        products.save(product);
        return ProductResponse.from(product);
    }

    @Transactional
    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public int publishDueProducts(java.time.Instant now, int limit) {
        var due = products.lockDueForPublication(now, limit);
        for (var product : due) {
            if (product.publishIfDue(now)) {
                products.save(product);
                index(product);
            }
        }
        return due.size();
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse archive(UUID id) {
        searchIndexLock.lockCatalogWrite();
        var product=findProduct(id);
        product.archive();
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse restore(UUID id) {
        searchIndexLock.lockCatalogWrite();
        var product=findProduct(id);
        product.restoreFromArchive();
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse updateCatalogMetadata(UUID id, CatalogMetadataRequest request) {
        searchIndexLock.lockCatalogWrite();
        var product = findProduct(id);
        BrandId brandId = request.brandId() == null ? null : new BrandId(request.brandId());
        if (brandId != null && brands.findById(brandId).isEmpty()) throw new CatalogNotFoundException("Brand not found: " + brandId.value());
        var categoryIds = request.categoryIds().stream().map(CategoryId::new).collect(java.util.stream.Collectors.toSet());
        for (CategoryId categoryId : categoryIds) {
            var category = categories.findById(categoryId).orElseThrow(() -> new CatalogNotFoundException("Category not found: " + categoryId.value()));
            if (!category.isActive()&&!product.getCategoryIds().contains(categoryId)) throw new IllegalArgumentException("Inactive categories cannot be newly assigned");
        }
        product.classify(brandId, categoryIds);
        var values = request.attributes() == null ? java.util.Map.<String, String>of() : request.attributes();
        product.getAttributes().keySet().stream().filter(code -> !values.containsKey(code)).toList().forEach(code -> {
            var definition = attributeDefinitions.findAll().stream().filter(candidate -> candidate.getCode().value().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Stored attribute definition is missing: " + code));
            if(!definition.isActive())throw new IllegalArgumentException("Reactivate attribute before removing its stored value: "+code);
            product.setAttribute(definition, null);
        });
        values.forEach((code, value) -> {
            var definition = attributeDefinitions.findAll().stream().filter(candidate -> candidate.getCode().value().equals(code)).findFirst()
                    .orElseThrow(() -> new CatalogNotFoundException("Attribute definition not found: " + code));
            if(!definition.isActive()&&java.util.Objects.equals(product.getAttributes().get(code),value))return;
            product.setAttribute(definition, value);
        });
        products.save(product);
        index(product);
        return ProductResponse.from(product);
    }

    @CacheEvict(cacheNames = "catalog-products", allEntries = true)
    public ProductResponse updateSeo(UUID id, SeoMetadataRequest request) {
        searchIndexLock.lockCatalogWrite();
        var product = findProduct(id);
        product.updateSeoMetadata(new io.artio.catalog.ProductSeoMetadata(request.slug(), request.title(), request.description()));
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
    public record SeoMetadataRequest(String slug, String title, String description) { }

    private Product findProduct(UUID id) {
        return products.findById(new ProductId(id))
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + id));
    }

    private void index(Product product) {
        ProductSearch productSearch = search.getIfAvailable();
        if (productSearch != null) productSearch.index(product);
    }
}
