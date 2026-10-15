package io.artio.api.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.catalog.LocaleCode;
import io.artio.catalog.Product;
import io.artio.catalog.ProductId;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.ProductSearch;
import io.artio.catalog.ports.ProductSearchResult;
import io.artio.catalog.ports.RebuildableProductSearch;
import io.artio.extensions.commerce.ProductSearchProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Maps the provider-neutral search contract to an enabled catalog.search.v1 plugin. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
        "'${artio.search.provider:extension}' == 'extension' && ${artio.search.enabled:false}")
public class ExtensionProductSearchAdapter implements RebuildableProductSearch {
    private static final String EXTENSION_POINT = "catalog.search.v1";
    private final ManagedExtensionInvoker extensions;
    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private final String pluginId;
    private final ThreadLocal<String> rebuildGeneration = new ThreadLocal<>();

    public ExtensionProductSearchAdapter(ManagedExtensionInvoker extensions, ProductRepository products,
            JdbcTemplate jdbc, @Value("${artio.search.extension-plugin-id:}") String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_SEARCH_EXTENSION_PLUGIN_ID must identify a registered catalog search plugin");
        }
        this.extensions = extensions;
        this.products = products;
        this.jdbc = jdbc;
        this.pluginId = pluginId;
    }

    @Override
    public void index(Product product) {
        ProductSearchProvider.ProductDocument document = new ProductSearchProvider.ProductDocument(
                product.getId().value().toString(), product.getSku().value(), product.getName().value(),
                product.getDescription().value(), product.getCategoryIds().stream().findFirst().map(id -> id.value().toString()).orElse(null),
                product.getBrandId() == null ? null : product.getBrandId().value().toString(), "base");
        indexDocument(document, product.isActive());
    }

    @Override
    public void indexLocalized(UUID productId, String sku, boolean active, LocaleCode locale,
            String name, String description) {
        indexDocument(new ProductSearchProvider.ProductDocument(productId.toString(), sku, name, description,
                null, null, locale.value()), active);
    }

    public void remove(String productId) {
        extensions.invoke(pluginId, EXTENSION_POINT, "remove", Map.of("productId", productId));
    }

    @Override
    public List<ProductSearchResult> search(String query, int limit) {
        return search(query, limit, new LocaleCode("en"));
    }

    @Override
    public List<ProductSearchResult> search(String query, int limit, LocaleCode locale) {
        int boundedSize = Math.max(1, Math.min(limit, 100));
        Map<String, Object> payload = new HashMap<>();
        payload.put("request", new ProductSearchProvider.SearchRequest(query, locale.value(), 0, boundedSize));
        String generation = rebuildGeneration.get();
        if (generation != null) payload.put("generation", generation);
        JsonNode response = extensions.invoke(pluginId, EXTENSION_POINT, "search", payload);
        JsonNode ids = response == null ? null : response.get("productIds");
        if (ids == null || !ids.isArray()) throw new io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException(
                "Search plugin response must contain a productIds array");
        if (ids.size() > boundedSize) throw new io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException(
                "Search plugin returned more products than requested");
        List<ProductSearchResult> results = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (JsonNode idNode : ids) {
            if (!idNode.isTextual()) throw new io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException(
                    "Search plugin returned an invalid product identifier");
            UUID id;
            try { id = UUID.fromString(idNode.asText()); }
            catch (IllegalArgumentException invalid) { throw new io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException("Search plugin returned an invalid product identifier"); }
            if (!seen.add(id)) continue;
            products.findById(new ProductId(id)).ifPresent(product -> results.add(hydrate(product, locale)));
        }
        return List.copyOf(results);
    }

    @Override
    public <T> T rebuild(java.util.function.Function<ProductSearch, T> replay) {
        JsonNode started = extensions.invoke(pluginId, EXTENSION_POINT, "beginRebuild", Map.of());
        String generation = started == null ? null : started.path("generation").asText();
        if (generation == null || !generation.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException(
                    "Search plugin did not return a valid rebuild generation");
        }
        rebuildGeneration.set(generation);
        try {
            T result = replay.apply(this);
            extensions.invoke(pluginId, EXTENSION_POINT, "commitRebuild", Map.of("generation", generation));
            return result;
        } catch (RuntimeException failure) {
            try { extensions.invoke(pluginId, EXTENSION_POINT, "abortRebuild", Map.of("generation", generation)); }
            catch (RuntimeException ignored) { /* Keep the original rebuild failure. */ }
            throw failure;
        } finally {
            rebuildGeneration.remove();
        }
    }

    private void indexDocument(ProductSearchProvider.ProductDocument document, boolean active) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("document", document);
        payload.put("active", active);
        String generation = rebuildGeneration.get();
        if (generation != null) payload.put("generation", generation);
        extensions.invoke(pluginId, EXTENSION_POINT, "index", payload);
    }

    private ProductSearchResult hydrate(Product product, LocaleCode locale) {
        var localized = jdbc.query("SELECT name,description FROM catalog_product_translations WHERE product_id=? AND locale=?",
                (rs, row) -> new LocalizedText(rs.getString("name"), rs.getString("description")),
                product.getId().value(), locale.value()).stream().findFirst()
                .or(() -> jdbc.query("SELECT name,description FROM catalog_product_translations WHERE product_id=? AND locale='en'",
                        (rs, row) -> new LocalizedText(rs.getString("name"), rs.getString("description")), product.getId().value()).stream().findFirst())
                .orElse(new LocalizedText(product.getName().value(), product.getDescription().value()));
        return new ProductSearchResult(product.getId().value(), localized.name(), localized.description(),
                product.getSku().value(), product.isActive());
    }

    private record LocalizedText(String name, String description) { }
}
