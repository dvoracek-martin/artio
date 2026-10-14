package io.artio.api.distributions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RegionalDistributionCatalogTests {
    private final RegionalDistributionCatalog catalog = new RegionalDistributionCatalog(new ObjectMapper());

    @Test
    void exposesVersionedSwissDefaultsAndOptionalProviderRecommendations() {
        var switzerland = catalog.find("switzerland-v1");
        assertThat(switzerland.countryCode()).isEqualTo("CH");
        assertThat(switzerland.currencyCode()).isEqualTo("CHF");
        assertThat(switzerland.defaultLocale()).isEqualTo("de-CH");
        assertThat(switzerland.availableLanguages()).containsExactly("de", "fr", "it", "en");
        assertThat(switzerland.timezone()).isEqualTo("Europe/Zurich");
        assertThat(switzerland.pluginRecommendations()).extracting(RegionalDistribution.PluginRecommendation::pluginId)
                .containsExactly("payments.stripe", "payments.twint", "shipping.swiss-post");
        assertThat(switzerland.pluginRecommendations()).allMatch(RegionalDistribution.PluginRecommendation::optional);
        assertThat(catalog.findAll()).containsExactly(switzerland);
    }

    @Test
    void rejectsUnknownDistributionIds() {
        assertThatThrownBy(() -> catalog.find("unknown-v1"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Regional distribution was not found");
    }
}
