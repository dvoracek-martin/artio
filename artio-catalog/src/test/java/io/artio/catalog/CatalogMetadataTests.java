package io.artio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogMetadataTests {
    @Test void categoryCannotParentItselfAndMoveRecordsChange() {
        var id = new CategoryId(UUID.randomUUID());
        assertThatThrownBy(() -> Category.create(id, new CategoryName("Audio"), id))
                .isInstanceOf(IllegalArgumentException.class);
        var category = Category.create(id, new CategoryName(" Audio "), null);
        var parent = new CategoryId(UUID.randomUUID());
        category.moveUnder(parent);
        assertThat(category.getParentId()).isEqualTo(parent);
        assertThat(category.getDomainEvents()).hasSize(2);
    }

    @Test void attributeDefinitionUsesStableMachineCodeAndCanBeRenamed() {
        assertThatThrownBy(() -> new AttributeCode("Screen Size"))
                .isInstanceOf(IllegalArgumentException.class);
        var definition = AttributeDefinition.create(new AttributeDefinitionId(UUID.randomUUID()),
                new AttributeCode("screen_size"), "Screen size", AttributeDefinition.Type.NUMBER, false);
        definition.rename("Diagonal");
        assertThat(definition.getLabel()).isEqualTo("Diagonal");
        assertThat(definition.getDomainEvents()).hasSize(2);
    }

    @Test void brandRejectsOverlongDescriptionAndTracksActiveState() {
        assertThatThrownBy(() -> Brand.create(new BrandId(UUID.randomUUID()), new BrandName("Acme"), "x".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        var brand = Brand.create(new BrandId(UUID.randomUUID()), new BrandName("Acme"), "Maker");
        brand.setActive(false);
        assertThat(brand.isActive()).isFalse();
        assertThat(brand.getDomainEvents()).hasSize(2);
    }

    @Test void productEnforcesTypedAttributeValuesAndStoresClassification() {
        var product = Product.create(new ProductId(UUID.randomUUID()), new ProductName("Display"),
                new ProductDescription("Panel"), new Sku("DSP-001"));
        var definition = AttributeDefinition.create(new AttributeDefinitionId(UUID.randomUUID()),
                new AttributeCode("diagonal"), "Diagonal", AttributeDefinition.Type.NUMBER, true);
        assertThatThrownBy(() -> product.setAttribute(definition, "large"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("number");
        product.setAttribute(definition, "27.5");
        var category = new CategoryId(UUID.randomUUID());
        var brand = new BrandId(UUID.randomUUID());
        product.classify(brand, java.util.Set.of(category));
        assertThat(product.getAttributes()).containsEntry("diagonal", "27.5");
        assertThat(product.getBrandId()).isEqualTo(brand);
        assertThat(product.getCategoryIds()).containsExactly(category);
    }
}
