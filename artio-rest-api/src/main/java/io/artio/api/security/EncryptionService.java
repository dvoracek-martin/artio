package io.artio.api.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** AES-256-GCM envelope encryption for application secrets stored in the database. */
@Service
public class EncryptionService {
    private static final int IV_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(@Value("${artio.security.encryption-key}") String configuredKey,
                             @Value("${spring.profiles.active:}") String activeProfiles) {
        if(configuredKey.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalStateException("ARTIO_ENCRYPTION_KEY must contain at least 32 bytes");
        if(java.util.Arrays.asList(activeProfiles.split(",")).contains("production")&&configuredKey.equals("local-development-encryption-secret-change-before-deploy"))throw new IllegalStateException("ARTIO_ENCRYPTION_KEY must be explicitly configured in production");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) { throw new IllegalStateException("AES key derivation failed", e); }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) { throw new IllegalStateException("Could not encrypt sensitive data", e); }
    }

    public String decrypt(String encoded) {
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
}
