package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicProductControllerTests {
    @Test
    void resolvesLocalizedPublishedProductByCanonicalSlug() {
        var translations = mock(ProductTranslationApplicationService.class);
        var expected = new ProductTranslationApplicationService.PublicProductView(
                UUID.randomUUID(), "Keramiktasse", "Handgefertigt", "CUP-1", "tasse", "Keramiktasse", "Handgefertigt");
        when(translations.resolvePublishedBySlug("tasse", "de")).thenReturn(expected);

        var actual = new PublicProductController(translations).findBySlug("tasse", "de");

        assertThat(actual).isEqualTo(expected);
        verify(translations).resolvePublishedBySlug("tasse", "de");
    }
}
