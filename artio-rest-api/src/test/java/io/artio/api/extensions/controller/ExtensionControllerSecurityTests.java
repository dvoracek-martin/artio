package io.artio.api.extensions.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExtensionControllerSecurityTests {
    private static final String TOKEN = "integration-controller-token-value-at-least-32-characters";

    @Test
    void rejectsMissingAndIncorrectCredentials() {
        var security = new ExtensionControllerSecurity(TOKEN);
        assertThatThrownBy(() -> security.authorize(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> security.authorize("Bearer incorrect-token-value"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsTheConfiguredBearerCredential() {
        new ExtensionControllerSecurity(TOKEN).authorize("Bearer " + TOKEN);
    }
}
