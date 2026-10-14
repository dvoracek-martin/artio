package io.artio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariantTests {
    @Test
    void createsVariantForAProductAndRecordsEvent() {
        var id = new VariantId(UUID.randomUUID());
        var productId = new ProductId(UUID.randomUUID());
        var name = new VariantName("Midnight / 256 GB");
        var sku = new Sku("PHONE-MID-256");

        var variant = Variant.create(id, productId, name, sku);

        assertThat(variant.getId()).isEqualTo(id);
        assertThat(variant.getProductId()).isEqualTo(productId);
        assertThat(variant.getName()).isEqualTo(name);
        assertThat(variant.getSku()).isEqualTo(sku);
        assertThat(variant.isActive()).isTrue();
        assertThat(variant.getDomainEvents()).containsExactly(new VariantCreated(id, productId, name, sku));
    }

    @Test
    void storesImmutableTypedCombinationValuesOnVariant() {
        var variant = Variant.create(new VariantId(UUID.randomUUID()), new ProductId(UUID.randomUUID()),
                new VariantName("Blue / Medium"), new Sku("SHIRT-BLU-M"), Map.of("size", "M", "color", "blue"));

        assertThat(variant.getAttributes()).containsExactlyInAnyOrderEntriesOf(Map.of("size", "M", "color", "blue"));
        assertThat(((VariantCreated) variant.getDomainEvents().getFirst()).attributes()).containsEntry("color", "blue");
        assertThatThrownBy(() -> variant.getAttributes().put("size", "L")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updatesCombinationAndDetailsOnlyWhenTheValueChanges() {
        var variant = variant();
        variant.update(new VariantName("Blue medium"), new Sku("ITEM-BLU-M"), Map.of("size", "M"));
        int eventCount = variant.getDomainEvents().size();
        variant.update(new VariantName("Blue medium"), new Sku("ITEM-BLU-M"), Map.of("size", "M"));

        assertThat(variant.getAttributes()).containsEntry("size", "M");
        assertThat(variant.getDomainEvents()).hasSize(eventCount);
    }

    @Test
    void rejectsMalformedOptionKeysAndBlankValues() {
        assertThatThrownBy(() -> Variant.create(new VariantId(UUID.randomUUID()), new ProductId(UUID.randomUUID()),
                new VariantName("Blue"), new Sku("ITEM-RED"), Map.of("Color", "red")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Variant.create(new VariantId(UUID.randomUUID()), new ProductId(UUID.randomUUID()),
                new VariantName("Blue"), new Sku("ITEM-RED"), Map.of("color", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void variantNameMustBePresentAndAtMost500Characters() {
        assertThatThrownBy(() -> new VariantName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VariantName("x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activationTransitionsRecordEventsAndRejectInvalidRepeats() {
        var variant = variant();
        assertThatThrownBy(variant::activate).isInstanceOf(IllegalStateException.class);

        variant.deactivate();
        variant.activate();

        assertThat(variant.isActive()).isTrue();
        assertThat(variant.getDomainEvents()).hasSize(3);
        assertThatThrownBy(variant::activate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconstitutionDoesNotEmitCreationEvent() {
        var now = Instant.parse("2025-01-01T00:00:00Z");
        var variant = Variant.reconstitute(new VariantId(UUID.randomUUID()), new ProductId(UUID.randomUUID()),
                new VariantName("Blue"), new Sku("ITEM-BLU"), false, now, now);

        assertThat(variant.isActive()).isFalse();
        assertThat(variant.getCreatedAt()).isEqualTo(now);
        assertThat(variant.getDomainEvents()).isEmpty();
    }

    private Variant variant() {
        return Variant.create(new VariantId(UUID.randomUUID()), new ProductId(UUID.randomUUID()),
                new VariantName("Blue"), new Sku("ITEM-BLU"));
    }
}
