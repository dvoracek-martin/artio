package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.artio.catalog.AttributeCode;
import io.artio.catalog.AttributeDefinition;
import io.artio.catalog.AttributeDefinitionId;
import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import io.artio.catalog.ports.AttributeDefinitionRepository;
import io.artio.catalog.ports.ProductRepository;
import io.artio.catalog.ports.VariantRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VariantApplicationServiceTests {
    private final ProductRepository products = mock(ProductRepository.class);
    private final VariantRepository variants = mock(VariantRepository.class);
    private final AttributeDefinitionRepository definitions = mock(AttributeDefinitionRepository.class);
    private final VariantApplicationService service = new VariantApplicationService(products, variants, definitions);

    @Test
    void createsVariantWithValidatedTypedOptions() {
        UUID productId = UUID.randomUUID();
        var product = product(productId);
        when(products.findById(new ProductId(productId))).thenReturn(Optional.of(product));
        when(definitions.findAll()).thenReturn(List.of(definition("color", AttributeDefinition.Type.TEXT),
                definition("size", AttributeDefinition.Type.NUMBER), definition("waterproof", AttributeDefinition.Type.BOOLEAN)));
        when(variants.findByProductId(new ProductId(productId))).thenReturn(List.of());

        var response = service.create(productId, new VariantRequest("Blue, 42", "SHIRT-BLU-42",
                Map.of("color", " blue ", "size", "42", "waterproof", "TRUE")));

        assertThat(response.attributes()).containsEntry("color", "blue").containsEntry("size", "42").containsEntry("waterproof", "true");
        verify(variants).save(org.mockito.ArgumentMatchers.argThat(value -> value.getAttributes().equals(response.attributes())));
    }

    @Test
    void rejectsDuplicateOptionCombinationsBeforePersistence() {
        UUID productId = UUID.randomUUID();
        var product = product(productId);
        var existing = io.artio.catalog.Variant.create(new io.artio.catalog.VariantId(UUID.randomUUID()),
                product.getId(), new io.artio.catalog.VariantName("Blue M"), new Sku("SHIRT-BLU-M"), Map.of("color", "blue"));
        when(products.findById(new ProductId(productId))).thenReturn(Optional.of(product));
        when(definitions.findAll()).thenReturn(List.of(definition("color", AttributeDefinition.Type.TEXT)));
        when(variants.findByProductId(new ProductId(productId))).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.create(productId,
                new VariantRequest("Blue M duplicate", "SHIRT-BLU-M2", Map.of("color", "blue"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same option combination");
        verify(variants, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsValuesThatDoNotMatchTheirAttributeType() {
        UUID productId = UUID.randomUUID();
        when(products.findById(new ProductId(productId))).thenReturn(Optional.of(product(productId)));
        when(definitions.findAll()).thenReturn(List.of(definition("size", AttributeDefinition.Type.NUMBER)));

        assertThatThrownBy(() -> service.create(productId,
                new VariantRequest("Invalid", "ITEM-INVALID", Map.of("size", "large"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
        verify(variants, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static Product product(UUID id) {
        return Product.create(new ProductId(id), new ProductName("Shirt"), new ProductDescription("Catalog test"),
                new Sku("PRODUCT-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)));
    }

    private static AttributeDefinition definition(String code, AttributeDefinition.Type type) {
        return AttributeDefinition.create(new AttributeDefinitionId(UUID.randomUUID()), new AttributeCode(code), code, type, false);
    }
}
