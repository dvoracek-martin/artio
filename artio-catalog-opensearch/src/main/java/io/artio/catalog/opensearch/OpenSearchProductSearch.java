package io.artio.catalog.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.catalog.Product;
import io.artio.catalog.ports.ProductSearch;
import io.artio.catalog.ports.ProductSearchResult;
import io.artio.catalog.ports.RebuildableProductSearch;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "artio.search.enabled", havingValue = "true")
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${artio.search.provider:opensearch}' != 'extension'")
public class OpenSearchProductSearch implements RebuildableProductSearch {
    private static final String INDEX = "artio-products";
    private final RestClient client;
    private final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock();

    public OpenSearchProductSearch(RestClient.Builder builder,
                                   org.springframework.core.env.Environment environment) {
        this.client = builder.baseUrl(environment.getProperty("artio.search.url", "http://localhost:9200")).build();
    }

    @Override public void index(Product product) {
        indexLock.readLock().lock();
        try { putProduct(INDEX, product); }
        finally { indexLock.readLock().unlock(); }
    }

    @Override public void indexLocalized(UUID productId, String sku, boolean active,
                                         io.artio.catalog.LocaleCode locale, String name, String description) {
        indexLock.readLock().lock();
        try { putLocalized(INDEX, productId, sku, active, locale, name, description); }
        finally { indexLock.readLock().unlock(); }
    }

    @Override public <T> T rebuild(Function<ProductSearch, T> replay) {
        indexLock.writeLock().lock();
        String generation = INDEX + "-generation-" + UUID.randomUUID().toString().replace("-", "");
        try {
            client.put().uri("/{index}", generation).retrieve().toBodilessEntity();
            ProductSearch generationWriter = new ProductSearch() {
                @Override public void index(Product product) { putProduct(generation, product); }
                @Override public void indexLocalized(UUID id, String sku, boolean active,
                        io.artio.catalog.LocaleCode locale, String name, String description) {
                    putLocalized(generation, id, sku, active, locale, name, description);
                }
                @Override public List<ProductSearchResult> search(String query, int limit) {
                    throw new UnsupportedOperationException("A rebuild writer cannot search");
                }
            };
            T result = replay.apply(generationWriter);
            client.post().uri("/{index}/_refresh", generation).retrieve().toBodilessEntity();
            switchAlias(generation);
            return result;
        } catch (RuntimeException failure) {
            deleteGeneration(generation);
            throw failure;
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private void switchAlias(String generation) {
        JsonNode existingAlias = null;
        try {
            existingAlias = client.get().uri("/_alias/{alias}", INDEX).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException notAnAlias) {
            if (notAnAlias.getStatusCode().value() != 404) throw notAnAlias;
        }
        var oldIndices = new java.util.ArrayList<String>();
        boolean aliasExists = false;
        if (existingAlias != null) {
            var indexNames = existingAlias.fieldNames();
            while (indexNames.hasNext()) {
                String oldIndex = indexNames.next();
                if (existingAlias.path(oldIndex).path("aliases").has(INDEX)) {
                    oldIndices.add(oldIndex);
                    aliasExists = true;
                }
            }
        }
        boolean concreteIndexExists = false;
        if (!aliasExists) {
            try {
                client.get().uri("/{index}", INDEX).retrieve().body(JsonNode.class);
                concreteIndexExists = true;
            } catch (RestClientResponseException missingIndex) {
                if (missingIndex.getStatusCode().value() != 404) throw missingIndex;
            }
            if (concreteIndexExists) oldIndices.add(INDEX);
        }
        if (aliasExists) {
            var actions = new java.util.ArrayList<Object>();
            oldIndices.forEach(oldIndex -> {
                actions.add(new AliasAction("remove", oldIndex, INDEX));
            });
            actions.add(new AliasAction("add", generation, INDEX));
            client.post().uri("/_aliases").body(new AliasRequest(actions)).retrieve().toBodilessEntity();
        } else if (concreteIndexExists) {
            client.post().uri("/_aliases").body(new AliasRequest(List.of(
                    new RemoveIndexAction(INDEX), new AliasAction("add", generation, INDEX))))
                    .retrieve().toBodilessEntity();
        } else {
            client.post().uri("/_aliases").body(new AliasRequest(List.of(new AliasAction("add", generation, INDEX))))
                    .retrieve().toBodilessEntity();
        }
        for (String oldIndex : oldIndices) deleteGeneration(oldIndex);
    }

    private void deleteGeneration(String generation) {
        try { client.delete().uri("/{index}", generation).retrieve().toBodilessEntity(); }
        catch (RuntimeException ignored) { /* Preserve the rebuild failure; abandoned generations are harmless. */ }
    }

    private void putProduct(String index, Product product) {
        client.put().uri("/{index}/_doc/{id}", index, product.getId().value() + "-base")
                .body(new ProductDocument(product.getId().value(), product.getName().value(),
                        product.getDescription().value(), product.getSku().value(), product.isActive(),
                        product.getBrandId() == null ? null : product.getBrandId().value(),
                        product.getCategoryIds().stream().map(id -> id.value()).toList(),
                        product.getAttributes(), String.join(" ", product.getAttributes().values()), "base"))
                .retrieve().toBodilessEntity();
    }

    private void putLocalized(String index, UUID productId, String sku, boolean active,
                              io.artio.catalog.LocaleCode locale, String name, String description) {
        client.put().uri("/{index}/_doc/{id}", index, productId + "-" + locale.value())
                .body(new ProductDocument(productId, name, description, sku, active,
                        null, List.of(), java.util.Map.of(), "", locale.value()))
                .retrieve().toBodilessEntity();
    }

    @Override public List<ProductSearchResult> search(String query, int limit) {
        return search(query, limit, new io.artio.catalog.LocaleCode("en"));
    }

    @Override public List<ProductSearchResult> search(String query, int limit, io.artio.catalog.LocaleCode locale) {
        indexLock.readLock().lock();
        try {
        List<ProductSearchResult> matching = searchLocale(query, limit, locale.value());
        if (!matching.isEmpty()) return matching;
        if (!locale.value().equals("en")) {
            matching = searchLocale(query, limit, "en");
            if (!matching.isEmpty()) return matching;
        }
        return searchLocale(query, limit, "base");
        } finally { indexLock.readLock().unlock(); }
    }

    private List<ProductSearchResult> searchLocale(String query, int limit, String locale) {
        JsonNode response = client.post().uri("/{index}/_search", INDEX)
                .body(new SearchRequest(new BoolQuery(new BoolClause(new MultiMatch(query),
                        new TermClause(java.util.Map.of("locale", locale)))), Math.max(1, Math.min(limit, 100))))
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
                                   String attributeText, String locale) {}
    private record SearchRequest(BoolQuery query, int size) {}
    private record BoolQuery(BoolClause bool) {}
    private record BoolClause(MultiMatch must, TermClause filter) {}
    private record TermClause(java.util.Map<String, String> term) {}
    private record MultiMatch(MultiMatchQuery multi_match) { MultiMatch(String text) { this(new MultiMatchQuery(text, List.of("name^3", "sku^2", "description", "attributeText"))); } }
    private record MultiMatchQuery(String query, List<String> fields) {}
    private record AliasRequest(List<Object> actions) {}
    private record AliasAction(String operation, String index, String alias) {
        @com.fasterxml.jackson.annotation.JsonValue
        Object json() { return java.util.Map.of(operation, java.util.Map.of("index", index, "alias", alias)); }
    }
    private record RemoveIndexAction(String index) {
        @com.fasterxml.jackson.annotation.JsonValue
        Object json() { return java.util.Map.of("remove_index", java.util.Map.of("index", index)); }
    }
}
