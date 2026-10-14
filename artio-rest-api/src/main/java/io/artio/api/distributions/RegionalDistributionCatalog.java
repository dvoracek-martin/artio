package io.artio.api.distributions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Loads and validates immutable distribution manifests packaged with the Core API. */
@Component
public class RegionalDistributionCatalog {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*-v[1-9][0-9]*");
    private final Map<String, RegionalDistribution> distributions;

    public RegionalDistributionCatalog(ObjectMapper mapper) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:distributions/*.json");
            List<RegionalDistribution> loaded = Arrays.stream(resources).map(resource -> read(mapper, resource)).toList();
            for (RegionalDistribution distribution : loaded) validate(distribution);
            this.distributions = loaded.stream().collect(Collectors.toUnmodifiableMap(
                    RegionalDistribution::id, distribution -> distribution));
            if (distributions.isEmpty()) throw new IllegalStateException("At least one regional distribution must be packaged");
        } catch (Exception failure) {
            throw new IllegalStateException("Could not load regional distribution manifests", failure);
        }
    }

    public List<RegionalDistribution> findAll() {
        return distributions.values().stream().sorted(java.util.Comparator.comparing(RegionalDistribution::id)).toList();
    }

    public RegionalDistribution find(String id) {
        RegionalDistribution distribution = distributions.get(id);
        if (distribution == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Regional distribution was not found");
        return distribution;
    }

    private static RegionalDistribution read(ObjectMapper mapper, Resource resource) {
        try (var input = resource.getInputStream()) {
            return mapper.readValue(input, RegionalDistribution.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid regional distribution manifest: " + resource.getFilename(), failure);
        }
    }

    private static void validate(RegionalDistribution distribution) {
        if (!"1".equals(distribution.manifestVersion())) throw new IllegalStateException("Unsupported regional distribution manifest version");
        if (distribution.id() == null || !ID.matcher(distribution.id()).matches()) throw new IllegalStateException("Invalid regional distribution id");
        if (distribution.name() == null || distribution.name().isBlank() || distribution.name().length() > 120) throw new IllegalStateException("Invalid regional distribution name");
        if (distribution.description() == null || distribution.description().length() > 1000) throw new IllegalStateException("Invalid regional distribution description");
        if (distribution.countryCode() == null || !Set.of(Locale.getISOCountries()).contains(distribution.countryCode())) throw new IllegalStateException("Invalid regional distribution country");
        try {
            if (!Currency.getInstance(distribution.currencyCode()).getCurrencyCode().equals(distribution.currencyCode())) throw new IllegalStateException("Invalid regional distribution currency");
            if (Locale.forLanguageTag(distribution.defaultLocale()).getLanguage().isBlank()) throw new IllegalStateException("Invalid regional distribution locale");
        } catch (RuntimeException failure) { throw new IllegalStateException("Invalid regional distribution currency or locale", failure); }
        if (!TimeZone.getTimeZone(distribution.timezone()).getID().equals(distribution.timezone())) throw new IllegalStateException("Invalid regional distribution time zone");
        if (distribution.availableLanguages().isEmpty() || distribution.availableLanguages().size() > 20) throw new IllegalStateException("Invalid regional language list");
        Set<String> languages = new java.util.HashSet<>();
        for (String language : distribution.availableLanguages()) {
            if (language == null || Locale.forLanguageTag(language).getLanguage().isBlank() || !languages.add(language)) throw new IllegalStateException("Invalid or duplicate regional language");
        }
        Set<String> pluginIds = new java.util.HashSet<>();
        for (var recommendation : distribution.pluginRecommendations()) {
            if (recommendation == null || recommendation.pluginId() == null
                    || !recommendation.pluginId().matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
                    || !pluginIds.add(recommendation.pluginId()) || recommendation.reason() == null
                    || recommendation.reason().isBlank() || recommendation.reason().length() > 500) {
                throw new IllegalStateException("Invalid regional plugin recommendation");
            }
        }
    }
}
