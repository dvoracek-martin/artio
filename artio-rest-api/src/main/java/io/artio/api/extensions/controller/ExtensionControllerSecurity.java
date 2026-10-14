package io.artio.api.extensions.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
final class ExtensionControllerSecurity {
    private final byte[] expectedToken;

    ExtensionControllerSecurity(@Value("${artio.extensions.controller.token:}") String token) {
        if (token.length() < 32) throw new IllegalStateException("Extension controller token must contain at least 32 characters");
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    void authorize(String authorization) {
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : "";
        if (!MessageDigest.isEqual(expectedToken, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal controller credential");
        }
    }
}
