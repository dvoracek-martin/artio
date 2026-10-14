package io.artio.api.security;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EncryptionServiceTests {
    private final EncryptionService encryption=new EncryptionService("test-key-material-is-long-enough-for-this-suite");
    @Test void encryptsWithRandomIvAndRoundTrips(){
        String value="secret that must not be stored in clear text";
        String first=encryption.encrypt(value),second=encryption.encrypt(value);
        assertNotEquals(first,second);
        assertNotEquals(value,first);
        assertEquals(value,encryption.decrypt(first));
    }
    @Test void rejectsTamperedCiphertext(){
        String encoded=encryption.encrypt("top-secret");
        String[] parts=encoded.split("\\.");
        byte[] bytes=java.util.Base64.getDecoder().decode(parts[2]);bytes[bytes.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->encryption.decrypt("v2."+parts[1]+"."+java.util.Base64.getEncoder().encodeToString(bytes)));
    }

    @Test void versionsCiphertextAndRewrapsPreviousKeys() {
        String oldKey="old-key-material-that-is-at-least-thirty-two-bytes";
        String newKey="new-key-material-that-is-at-least-thirty-two-bytes";
        var oldEncryption=new EncryptionService(oldKey);
        String oldCiphertext=oldEncryption.encrypt("provider-secret");
        String oldId=EncryptionService.keyId(oldKey);
        var rotating=new EncryptionService(newKey,"{\""+oldId+"\":\""+oldKey+"\"}");

        assertTrue(oldCiphertext.startsWith("v2."+oldId+"."));
        assertEquals("provider-secret",rotating.decrypt(oldCiphertext));
        String rotated=rotating.reencryptWithActiveKey(oldCiphertext);
        assertTrue(rotating.isEncryptedWithActiveKey(rotated));
        assertEquals("provider-secret",rotating.decrypt(rotated));
        assertEquals(rotated,rotating.reencryptWithActiveKey(rotated));
    }

    @Test void decryptsLegacyUnversionedCiphertextsDuringMigration() throws Exception {
        String key="legacy-key-material-that-is-at-least-thirty-two-bytes";
        String legacy=legacyEncrypt(key,"legacy-mfa-secret");
        var rotating=new EncryptionService("active-key-material-that-is-at-least-thirty-two-bytes",
                "{\""+EncryptionService.keyId(key)+"\":\""+key+"\"}");
        assertEquals("legacy-mfa-secret",rotating.decrypt(legacy));
        assertTrue(rotating.isEncryptedWithActiveKey(rotating.reencryptWithActiveKey(legacy)));
    }

    @Test void pluginCredentialsRemainStableWhenTheEncryptionKeyChanges() {
        String stable="installation-extension-auth-key-that-is-thirty-two-bytes";
        var before=new ExtensionCredentialService(stable);
        var after=new ExtensionCredentialService(stable);
        assertEquals(before.derive("payments.example",2),after.derive("payments.example",2));
        assertNotEquals(before.derive("payments.example"),before.derive("shipping.example"));
    }

    private static String legacyEncrypt(String key, String plaintext) throws Exception {
        byte[] aes=java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv=new byte[12];new java.security.SecureRandom().nextBytes(iv);
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(aes,"AES"),new javax.crypto.spec.GCMParameterSpec(128,iv));
        byte[] encrypted=cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());
    }
}
