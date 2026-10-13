package io.artio.catalog.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.catalog.Product;
import io.artio.catalog.ports.ProductSearch;
import io.artio.catalog.ports.ProductSearchResult;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "artio.search.enabled", havingValue = "true")
public class OpenSearchProductSearch implements ProductSearch {
    private static final String INDEX = "artio-products";
    private final RestClient client;

    public OpenSearchProductSearch(RestClient.Builder builder,
                                   org.springframework.core.env.Environment environment) {
        this.client = builder.baseUrl(environment.getProperty("artio.search.url", "http://localhost:9200")).build();
    }

    @Override public void index(Product product) {
        client.put().uri("/{index}/_doc/{id}", INDEX, product.getId().value())
                .body(new ProductDocument(product.getId().value(), product.getName().value(),
                        product.getDescription().value(), product.getSku().value(), product.isActive(),
                        product.getBrandId() == null ? null : product.getBrandId().value(),
                        product.getCategoryIds().stream().map(id -> id.value()).toList(),
                        product.getAttributes(), String.join(" ", product.getAttributes().values())))
                .retrieve().toBodilessEntity();
    }

    @Override public List<ProductSearchResult> search(String query, int limit) {
        JsonNode response = client.post().uri("/{index}/_search", INDEX)
                .body(new SearchRequest(new MultiMatch(query), Math.max(1, Math.min(limit, 100))))
                .retrieve().body(JsonNode.class);
        if (response == null) return List.of();
        JsonNode hits = response.path("hits").path("hits");
        var results = new java.util.ArrayList<ProductSearchResult>();
        hits.forEach(hit -> {
            JsonNode source = hit.path("_source");
            results.add(new ProductSearchResult(UUID.fromString(source.path("id").asText()),
                    source.path("name").asText(), source.path("description").isNull() ? null : source.path("description").asText(),
                    source.path("sku").asText(), source.path("active").asBoolean()));
        });
        return List.copyOf(results);
    }

    private record ProductDocument(UUID id, String name, String description, String sku, boolean active,
                                   UUID brandId, List<UUID> categoryIds, java.util.Map<String, String> attributes,
                                   String attributeText) {}
    private record SearchRequest(MultiMatch query, int size) {}
    private record MultiMatch(MultiMatchQuery multi_match) { MultiMatch(String text) { this(new MultiMatchQuery(text, List.of("name^3", "sku^2", "description", "attributeText"))); } }
    private record MultiMatchQuery(String query, List<String> fields) {}
}
