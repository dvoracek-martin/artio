package io.artio.api.security;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TotpTests {
    @Test void verifiesRfc6238SixDigitVectorAndRejectsWrongCode(){
        String secret=Base64.getEncoder().encodeToString("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertTrue(Totp.verify(secret,"287082",Instant.ofEpochSecond(59)));
        assertFalse(Totp.verify(secret,"287083",Instant.ofEpochSecond(59)));
        assertFalse(Totp.verify(secret,"287082",Instant.ofEpochSecond(120)));
    }

    @Test void returnsBase32ForAuthenticatorSetup(){
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",Totp.base32(Base64.getEncoder().encodeToString("12345678901234567890".getBytes(StandardCharsets.US_ASCII))));
    }
}
