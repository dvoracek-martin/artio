package io.artio.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTests {
    @Test void seoMetadataRequiresCanonicalSlugAndBoundsOptionalCopy() {
        var seo = new ProductSeoMetadata("brass-desk-lamp", "Brass Desk Lamp", "A crafted brass reading light.");
        assertThat(seo.slug()).isEqualTo("brass-desk-lamp");
        assertThatThrownBy(() -> new ProductSeoMetadata("Brass Desk Lamp", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductSeoMetadata("lamp", "x".repeat(181), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProductSeoMetadata("lamp", null, "x".repeat(321)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void seoMetadataChangesAreRecordedAsProductDomainEvents() {
        var product = product();
        var metadata = new ProductSeoMetadata("workstation", "Workstation", "A reliable computer.");
        product.updateSeoMetadata(metadata);
        assertThat(product.getSeoMetadata()).isEqualTo(metadata);
        assertThat(product.getDomainEvents()).last().isEqualTo(new ProductSeoMetadataUpdated(product.getId(), null, metadata));
    }

    @Test void archivingPreservesProductIdentityAndRestoreReturnsItAsInactiveDraft() {
        var product=product();
        product.archive();
        assertThat(product.isArchived()).isTrue();
        assertThat(product.isActive()).isFalse();
        assertThat(product.getArchivedAt()).isNotNull();
        assertThatThrownBy(product::activate).isInstanceOf(IllegalStateException.class);
        product.restoreFromArchive();
        assertThat(product.isArchived()).isFalse();
        assertThat(product.getArchivedAt()).isNull();
        assertThat(product.isActive()).isFalse();
        assertThat(product.getSku().value()).isEqualTo("LAP-001");
    }

    @Test
    void createsAnActiveProductAndRecordsCreationEvent() {
        var id = new ProductId(UUID.randomUUID());
        var name = new ProductName("Laptop");
        var description = new ProductDescription("High-performance laptop");
        var sku = new Sku("LAP-001");

        var product = Product.create(id, name, description, sku);

        assertThat(product.getId()).isEqualTo(id);
        assertThat(product.getName()).isEqualTo(name);
        assertThat(product.getDescription()).isEqualTo(description);
        assertThat(product.getSku()).isEqualTo(sku);
        assertThat(product.isActive()).isTrue();
        assertThat(product.getCreatedAt()).isNotNull();
        assertThat(product.getUpdatedAt()).isEqualTo(product.getCreatedAt());
        assertThat(product.getDomainEvents()).containsExactly(new ProductCreated(id, name, description, sku));
    }

    @Test
    void scheduledPublicationActivatesOnlyWhenDueAndCanBeCancelled() {
        var product = product();
        product.deactivate();
        var now = java.time.Instant.parse("2026-10-14T10:00:00Z");
        var publishAt = now.plusSeconds(60);
        product.schedulePublication(publishAt, now);
        assertThat(product.getScheduledPublishAt()).isEqualTo(publishAt);
        assertThat(product.publishIfDue(publishAt.minusSeconds(1))).isFalse();
        assertThat(product.isActive()).isFalse();
        assertThat(product.publishIfDue(publishAt)).isTrue();
        assertThat(product.isActive()).isTrue();
        assertThat(product.getScheduledPublishAt()).isNull();
        assertThat(product.getDomainEvents()).last().isEqualTo(new ProductPublished(product.getId(), publishAt));

        product.deactivate();
        product.schedulePublication(publishAt.plusSeconds(60), now);
        product.cancelScheduledPublication(now);
        assertThat(product.getScheduledPublishAt()).isNull();
        assertThatThrownBy(() -> product.cancelScheduledPublication(now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scheduledPublicationRequiresAFutureTimeAndManualActivationCancelsIt() {
        var product = product();
        product.deactivate();
        var now = java.time.Instant.now();
        assertThatThrownBy(() -> product.schedulePublication(now, now)).isInstanceOf(IllegalArgumentException.class);
        product.schedulePublication(now.plusSeconds(60), now);
        product.activate();
        assertThat(product.isActive()).isTrue();
        assertThat(product.getScheduledPublishAt()).isNull();
    }

    @Test
    void productNameCannotBeEmptyOrBlank() {
        assertThatThrownBy(() -> new ProductName(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name required");
        assertThatThrownBy(() -> new ProductName("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name required");
    }

    @Test
    void productNameCannotExceed500Characters() {
        assertThatThrownBy(() -> new ProductName("x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Name too long");
    }

    @Test
    void descriptionMayBeAbsentButCannotExceed5000Characters() {
        assertThat(new ProductDescription(null).value()).isNull();
        assertThatThrownBy(() -> new ProductDescription("x".repeat(5_001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Description too long");
    }

    @Test
    void skuMustUseUppercaseLettersDigitsAndHyphens() {
        assertThat(new Sku("LAP-001").value()).isEqualTo("LAP-001");
        assertThatThrownBy(() -> new Sku("invalid-sku-123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid SKU format");
        assertThatThrownBy(() -> new Sku("AB"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid SKU format");
    }

    @Test
    void productIdMustNotBeNull() {
        assertThatThrownBy(() -> new ProductId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deactivatesAndReactivatesProductWithEvents() {
        var product = product();

        product.deactivate();
        assertThat(product.isActive()).isFalse();
        product.activate();

        assertThat(product.isActive()).isTrue();
        assertThat(product.getDomainEvents()).hasSize(3)
                .contains(new ProductDeactivated(product.getId()), new ProductActivated(product.getId()));
    }

    @Test
    void cannotRepeatActivationOrDeactivation() {
        var product = product();
        assertThatThrownBy(product::activate).isInstanceOf(IllegalStateException.class).hasMessage("Already active");

        product.deactivate();
        assertThatThrownBy(product::deactivate)
                .isInstanceOf(IllegalStateException.class).hasMessage("Already inactive");
    }

    @Test
    void renameChangesTheProductAndRecordsOldAndNewNames() {
        var product = product();
        var oldName = product.getName();
        var newName = new ProductName("Workstation");

        product.rename(newName);

        assertThat(product.getName()).isEqualTo(newName);
        assertThat(product.getDomainEvents()).last().isEqualTo(new ProductRenamed(product.getId(), oldName, newName));
    }

    @Test
    void renamingToTheSameNameDoesNotRecordAnEvent() {
        var product = product();
        var originalEventCount = product.getDomainEvents().size();

        product.rename(product.getName());

        assertThat(product.getDomainEvents()).hasSize(originalEventCount);
    }

    @Test
    void updatingDescriptionChangesTheProductAndRecordsTheChange() {
        var product = product();
        var previous = product.getDescription();
        var replacement = new ProductDescription("Portable workstation");

        product.updateDescription(replacement);

        assertThat(product.getDescription()).isEqualTo(replacement);
        assertThat(product.getDomainEvents()).last()
                .isEqualTo(new ProductDescriptionUpdated(product.getId(), previous, replacement));
    }

    @Test
    void aggregateReturnsAnImmutableEventSnapshotAndCanClearPendingEvents() {
        var product = product();
        var events = product.getDomainEvents();

        assertThatThrownBy(() -> events.clear()).isInstanceOf(UnsupportedOperationException.class);
        product.clearDomainEvents();
        assertThat(product.getDomainEvents()).isEmpty();
    }

    private Product product() {
        return Product.create(new ProductId(UUID.randomUUID()), new ProductName("Laptop"),
                new ProductDescription("High-performance laptop"), new Sku("LAP-001"));
    }
}
