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
        byte[] bytes=java.util.Base64.getDecoder().decode(encoded);bytes[bytes.length-1]^=1;
        assertThrows(IllegalArgumentException.class,()->encryption.decrypt(java.util.Base64.getEncoder().encodeToString(bytes)));
    }
}
