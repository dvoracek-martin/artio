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
}
