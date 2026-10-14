package io.artio.api.users;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.api.security.Totp;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth/mfa/totp")
public class MfaController {
    private final JdbcTemplate jdbc;
    private final EncryptionService encryption;
    private final ObjectMapper mapper;
    private final BCryptPasswordEncoder backupCodeEncoder = new BCryptPasswordEncoder();

    public MfaController(JdbcTemplate jdbc, EncryptionService encryption, ObjectMapper mapper) { this.jdbc=jdbc; this.encryption=encryption; this.mapper=mapper; }

    @PostMapping("/enrollment") @Transactional
    public Enrollment enroll(JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),userId);
        var existing=jdbc.query("SELECT verified FROM mfa_methods WHERE user_id=? FOR UPDATE",(rs,n)->rs.getBoolean(1),userId);
        if (!existing.isEmpty()&&existing.getFirst()) throw new ResponseStatusException(HttpStatus.CONFLICT,"An MFA method is already enrolled");
        String secret = Totp.newSecret();
        if(existing.isEmpty())jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret) VALUES (?,?)", userId, encryption.encrypt(secret));
        else jdbc.update("UPDATE mfa_methods SET encrypted_secret=?,verified=FALSE,verified_at=NULL,backup_code_hashes='[]'::jsonb WHERE user_id=?",encryption.encrypt(secret),userId);
        String email = auth.getToken().getClaimAsString("email");
        String uri = "otpauth://totp/Artio:" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8) + "?secret=" + Totp.base32(secret) + "&issuer=Artio&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(Totp.base32(secret), uri, "TOTP", 30);
    }

    @PostMapping("/verify") @Transactional
    public Map<String,Object> verify(@RequestBody CodeRequest request, JwtAuthenticationToken auth) {
        UUID userId=userId(auth); String secret=loadSecret(userId);
        if (!Totp.verify(secret, request.code(), Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired TOTP code");
        List<String> backup=new ArrayList<>(); List<String> hashes=new ArrayList<>();
        for(int i=0;i<10;i++){String code=randomCode();backup.add(code);hashes.add(backupCodeEncoder.encode(code));}
        try {
            jdbc.update("UPDATE mfa_methods SET verified=TRUE,verified_at=now(),backup_code_hashes=?::jsonb WHERE user_id=?",mapper.writeValueAsString(hashes),userId);
        } catch(Exception e){throw new IllegalStateException("Could not save backup codes",e);}
        return Map.of("enabled",true,"backupCodes",backup,"warning","Save these one-time backup codes now. They cannot be retrieved again.");
    }

    @DeleteMapping @Transactional
    public void disable(@RequestBody CodeRequest request, JwtAuthenticationToken auth) {
        UUID userId=userId(auth); String secret=loadSecret(userId);
        String role=jdbc.queryForObject("SELECT role FROM users WHERE id=?",String.class,userId);
        if(java.util.List.of("ADMIN","SUPERADMIN").contains(role))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Privileged accounts must keep multi-factor authentication enabled");
        if (!Totp.verify(secret,request.code(),Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid TOTP code is required to disable MFA");
        jdbc.update("DELETE FROM mfa_methods WHERE user_id=?",userId);
    }

    private String loadSecret(UUID id){return jdbc.query("SELECT encrypted_secret FROM mfa_methods WHERE user_id=?",rs->{if(!rs.next())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MFA is not enrolled");return encryption.decrypt(rs.getString(1));},id);}
    public static UUID userId(JwtAuthenticationToken auth){return UUID.fromString(auth.getToken().getSubject());}
    private String randomCode(){byte[] bytes=new byte[8];new java.security.SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public record Enrollment(String base32Secret,String otpauthUri,String algorithm,int periodSeconds){}
    public record CodeRequest(String code){}
}
