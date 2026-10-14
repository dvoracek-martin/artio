package io.artio.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Stable plugin-scoped HMAC credentials, independent from the rotatable database encryption key. */
@Service
public final class ExtensionCredentialService {
    private final SecretKeySpec key;

    public ExtensionCredentialService(@Value("${artio.security.extension-auth-key}") String material) {
        SecretConfigurationValidator.requireStrongSecret(material, "ARTIO_EXTENSION_AUTH_KEY");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(digest, "HmacSHA256"));
            this.key = new SecretKeySpec(derivation.doFinal("artio-extension-auth-key-v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (Exception failure) {
            throw new IllegalStateException("Extension credential key derivation failed", failure);
        }
    }

    public String derive(String pluginId) { return derive(pluginId, 1); }

    public String derive(String pluginId, int generation) {
        if (pluginId == null || pluginId.isBlank() || generation < 1) throw new IllegalArgumentException("Invalid extension credential identity");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            String identity = generation == 1 ? "artio-extension-v1:" + pluginId : "artio-extension-v1:" + pluginId + ":" + generation;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not derive extension credential", failure);
        }
    }
}
