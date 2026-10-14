package io.artio.api.licensing;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads public Ed25519 trust anchors only. The issuer's private key never belongs in this process. */
public final class LicenseVerificationKeys {
    private LicenseVerificationKeys() { }

    public static Map<String, PublicKey> load(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return Map.of();
        try (var input = Files.newInputStream(Path.of(configuredPath))) {
            byte[] bytes = input.readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException();
            var mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
            try (var parser = mapper.createParser(bytes)) {
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(parser);
                if (root == null || !root.isObject() || root.size() > 8 || parser.nextToken() != null) throw new IllegalArgumentException();
                var result = new LinkedHashMap<String, PublicKey>();
                var entries = root.fields();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    if (!entry.getKey().matches("[A-Za-z0-9._-]{1,64}") || !entry.getValue().isTextual()) throw new IllegalArgumentException();
                    String encoded = entry.getValue().textValue();
                    byte[] decoded = Base64.getDecoder().decode(encoded);
                    if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) throw new IllegalArgumentException();
                    PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decoded));
                    if (!(key instanceof java.security.interfaces.EdECPublicKey edKey)
                            || !"Ed25519".equals(edKey.getParams().getName())) throw new IllegalArgumentException();
                    result.put(entry.getKey(), key);
                }
                return Map.copyOf(result);
            }
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid license verification key configuration");
        }
    }
}
