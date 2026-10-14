package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.ports.ProductRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ExtensionProductSearchAdapterTests {
    private final ManagedExtensionInvoker invoker = mock(ManagedExtensionInvoker.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExtensionProductSearchAdapter adapter = new ExtensionProductSearchAdapter(
            invoker, products, jdbc, "catalog.search.example");

    @Test
    void emitsProviderNeutralProductDocumentsAndActiveState() {
        var product = Product.create(new ProductId(UUID.randomUUID()), new ProductName("Desk"),
                new ProductDescription("Oak desk"), new Sku("DESK-1"));

        adapter.index(product);

        var payload = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(invoker).invoke(eq("catalog.search.example"), eq("catalog.search.v1"), eq("index"), payload.capture());
        assertThat(payload.getValue()).containsEntry("active", true);
        assertThat(mapper.valueToTree(payload.getValue()).at("/document/name").asText()).isEqualTo("Desk");
        assertThat(mapper.valueToTree(payload.getValue()).at("/document/locale").asText()).isEqualTo("base");
    }

    @Test
    void delegatesRebuildGenerationAndReturnsPluginProductIds() throws Exception {
        when(invoker.invoke(eq("catalog.search.example"), eq("catalog.search.v1"), eq("beginRebuild"), any()))
                .thenReturn(mapper.readTree("{\"generation\":\"generation-42\"}"));
        when(invoker.invoke(eq("catalog.search.example"), eq("catalog.search.v1"), eq("search"), any()))
                .thenReturn(mapper.readTree("{\"productIds\":[]}"));
        when(invoker.invoke(eq("catalog.search.example"), eq("catalog.search.v1"), eq("commitRebuild"), any()))
                .thenReturn(mapper.createObjectNode());

        String result = adapter.rebuild(writer -> {
            writer.search("desk", 10);
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(adapter.search("desk", 10)).isEmpty();
        var captured = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(invoker, org.mockito.Mockito.times(2)).invoke(eq("catalog.search.example"), eq("catalog.search.v1"),
                eq("search"), captured.capture());
        assertThat(captured.getAllValues().getFirst()).containsEntry("generation", "generation-42");
    }
}
