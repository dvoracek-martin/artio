package io.artio.api.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** AES-256-GCM envelope encryption for application secrets stored in the database. */
@Service
public class EncryptionService {
    private static final int IV_BYTES = 12;
    private final Map<String, SecretKeySpec> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public EncryptionService(@Value("${artio.security.encryption-key}") String configuredKey,
            @Value("${artio.security.encryption-keyring:}") String keyringJson) {
        SecretConfigurationValidator.requireStrongSecret(configuredKey, "ARTIO_ENCRYPTION_KEY");
        try {
            Map<String, SecretKeySpec> loaded = new LinkedHashMap<>();
            this.activeKeyId = keyId(configuredKey);
            loaded.put(activeKeyId, aesKey(configuredKey));
            if (keyringJson != null && !keyringJson.isBlank()) {
                String keyringContents = keyringJson.strip();
                if (!keyringContents.startsWith("{")) {
                    var keyringPath = java.nio.file.Path.of(keyringContents);
                    if (java.nio.file.Files.size(keyringPath) > 16_384) throw new IllegalArgumentException("Encryption keyring file exceeds 16 KiB");
                    keyringContents = java.nio.file.Files.readString(keyringPath, StandardCharsets.UTF_8);
                }
                if (keyringContents.getBytes(StandardCharsets.UTF_8).length > 16_384)
                    throw new IllegalArgumentException("Encryption keyring exceeds 16 KiB");
                Map<String, String> previous = new ObjectMapper().readValue(keyringContents, new TypeReference<>() { });
                if (previous.size() > 8) throw new IllegalArgumentException("Encryption keyring may contain at most 8 previous keys");
                for (var entry : previous.entrySet()) {
                    String id = entry.getKey();
                    String material = entry.getValue();
                    SecretConfigurationValidator.requireStrongSecret(material, "ARTIO_ENCRYPTION_KEYRING[" + id + "]");
                    if (!keyId(material).equals(id)) throw new IllegalArgumentException("Encryption keyring id does not match its key material");
                    loaded.putIfAbsent(id, aesKey(material));
                }
            }
            this.keys = Map.copyOf(loaded);
        } catch (Exception e) { throw new IllegalStateException("Could not initialize encryption keyring", e); }
    }

    public EncryptionService(String configuredKey) {
        this(configuredKey, "");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v2." + activeKeyId + "." + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) { throw new IllegalStateException("Could not encrypt sensitive data", e); }
    }

    public String decrypt(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("Could not decrypt sensitive data");
        if (encoded.startsWith("v2.")) {
            int separator = encoded.indexOf('.', 3);
            if (separator < 0) throw new IllegalArgumentException("Could not decrypt sensitive data");
            String id = encoded.substring(3, separator);
            SecretKeySpec selected = keys.get(id);
            if (selected == null) throw new IllegalArgumentException("Ciphertext requires an unavailable encryption key");
            return decryptPayload(encoded.substring(separator + 1), selected);
        }
        for (SecretKeySpec candidate : keys.values()) {
            try { return decryptPayload(encoded, candidate); }
            catch (IllegalArgumentException ignored) { /* Legacy envelopes do not carry a key id. */ }
        }
        throw new IllegalArgumentException("Could not decrypt sensitive data");
    }

    public boolean isEncryptedWithActiveKey(String encoded) {
        return encoded != null && encoded.startsWith("v2." + activeKeyId + ".");
    }

    public String reencryptWithActiveKey(String encoded) {
        String plaintext = decrypt(encoded);
        return isEncryptedWithActiveKey(encoded) ? encoded : encrypt(plaintext);
    }

    public String activeKeyId() { return activeKeyId; }

    private static String decryptPayload(String encoded, SecretKeySpec key) {
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= IV_BYTES + 16) throw new IllegalArgumentException("Invalid ciphertext");
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES]; buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()]; buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalArgumentException("Could not decrypt sensitive data", e); }
    }

    private static SecretKeySpec aesKey(String material) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    public static String keyId(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) { throw new IllegalStateException("Could not identify encryption key", failure); }
    }

}
