package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.ProductSearch;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class CatalogSearchReindexServiceTests {
    @Test
    void indexesProductsAndEveryPersistedLocaleDocument() throws Exception {
        UUID id = UUID.randomUUID();
        Product product = Product.create(new ProductId(id), new ProductName("Coffee"),
                new ProductDescription("Roasted beans"), new Sku("COFFEE-1"));
        ProductRepository repository = mock(ProductRepository.class);
        ProductSearch search = mock(ProductSearch.class);
        ObjectProvider<ProductSearch> searchProvider = mock(ObjectProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(repository.findAll()).thenReturn(List.of(product));
        when(searchProvider.getIfAvailable()).thenReturn(search);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(id))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultSetExtractor<Integer> extractor = invocation.getArgument(1);
            ResultSet rows = mock(ResultSet.class);
            AtomicInteger position = new AtomicInteger();
            when(rows.next()).thenAnswer(ignored -> position.getAndIncrement() == 0);
            when(rows.getString("locale")).thenReturn("de");
            when(rows.getString("name")).thenReturn("Kaffee");
            when(rows.getString("description")).thenReturn("Geröstete Bohnen");
            return extractor.extractData(rows);
        });

        var result = new CatalogSearchReindexService(repository, searchProvider, jdbc).reindex();

        assertThat(result.productsIndexed()).isEqualTo(1);
        assertThat(result.translationsIndexed()).isEqualTo(1);
        verify(search).index(product);
        verify(search).indexLocalized(id, "COFFEE-1", true,
                new io.artio.catalog.LocaleCode("de"), "Kaffee", "Geröstete Bohnen");
    }
}
