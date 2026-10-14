package io.artio.api;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test-only TOTP generation for authenticated integration scenarios. */
public final class TestTotp {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private TestTotp() { }

    public static String code(String base64Secret, Instant now) {
        try {
            byte[] key=Base64.getDecoder().decode(base64Secret);
            Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(key,"HmacSHA1"));
            byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(now.getEpochSecond()/30).array());
            int offset=hash[hash.length-1]&0x0f;
            int binary=((hash[offset]&0x7f)<<24)|((hash[offset+1]&0xff)<<16)|((hash[offset+2]&0xff)<<8)|(hash[offset+3]&0xff);
            return String.format("%06d",binary%1_000_000);
        } catch(Exception failure) { throw new IllegalStateException("Could not create integration TOTP",failure); }
    }

    public static String codeFromBase32(String secret,Instant now) {
        byte[] bytes=new byte[secret.length()*5/8];int buffer=0,bits=0,index=0;
        for(char ch:secret.toUpperCase(java.util.Locale.ROOT).toCharArray()){
            int value=BASE32.indexOf(ch);if(value<0)throw new IllegalArgumentException("Invalid Base32 TOTP secret");
            buffer=(buffer<<5)|value;bits+=5;if(bits>=8){bytes[index++]=(byte)(buffer>>(bits-8));bits-=8;}
        }
        return code(Base64.getEncoder().withoutPadding().encodeToString(bytes),now);
    }
}
