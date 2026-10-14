package io.artio.api.distributions;

import java.util.List;

/** Versioned, non-secret regional defaults and optional plugin discovery metadata. */
public record RegionalDistribution(String manifestVersion, String id, String name, String description,
        String countryCode, String currencyCode, String defaultLocale, List<String> availableLanguages,
        String timezone, List<PluginRecommendation> pluginRecommendations) {
    public RegionalDistribution {
        availableLanguages = availableLanguages == null ? List.of() : List.copyOf(availableLanguages);
        pluginRecommendations = pluginRecommendations == null ? List.of() : List.copyOf(pluginRecommendations);
    }

    public record PluginRecommendation(String pluginId, String reason, boolean optional) { }
}
