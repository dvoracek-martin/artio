package io.artio.api.extensions;

import io.artio.extensions.PluginManifestValidator;
import io.artio.extensions.PluginConfigurationValidator;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtensionManagementConfiguration {
    @Bean
    PluginManifestValidator pluginManifestValidator(
            @Value("${artio.extensions.trusted-registries:}") String configuredRegistries) {
        Set<String> trustedRegistries = Arrays.stream(configuredRegistries.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new PluginManifestValidator(trustedRegistries);
    }

    @Bean
    PluginConfigurationValidator pluginConfigurationValidator() {
        return new PluginConfigurationValidator();
    }

    @Bean
    OciArchiveInspector ociArchiveInspector(
            @Value("${artio.extensions.archive.maximum-bytes:2147483648}") long maximumArchiveBytes,
            @Value("${artio.extensions.archive.maximum-expanded-bytes:8589934592}") long maximumExpandedBytes,
            @Value("${artio.extensions.archive.maximum-expansion-ratio:200}") long maximumExpansionRatio,
            @Value("${artio.extensions.archive.maximum-entries:100000}") int maximumEntries,
            @Value("${artio.extensions.archive.maximum-images:16}") int maximumImages,
            @Value("${artio.extensions.archive.maximum-json-bytes:4194304}") int maximumJsonBytes) {
        return new OciArchiveInspector(new OciArchiveInspector.Limits(maximumArchiveBytes, maximumExpandedBytes,
                maximumExpansionRatio, maximumEntries, maximumImages, maximumJsonBytes));
    }
}
