package io.artio.api.users;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.api.security.Totp;
import io.artio.users.Email;
import io.artio.users.User;
import io.artio.users.UserId;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final JwtEncoder jwtEncoder;
    private final ObjectMapper mapper;
    private final EncryptionService encryption;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    public AuthController(JdbcTemplate jdbc, JwtEncoder jwtEncoder, ObjectMapper mapper, EncryptionService encryption) { this.jdbc = jdbc; this.jwtEncoder = jwtEncoder; this.mapper=mapper; this.encryption=encryption; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE role IN ('ADMIN','SUPERADMIN'))", Boolean.class))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer registration is unavailable until the store administrator completes first-run setup");
        }
        if (request == null || request.password() == null || request.password().length() < 12 || request.password().length() > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain between 12 and 72 characters");
        try {
            User user = User.registerCustomer(new UserId(UUID.randomUUID()), new Email(request.email()), passwords.encode(request.password()), request.firstName(), request.lastName());
            jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'CUSTOMER',?)", user.getId().value(), user.email().value(), user.passwordHash(), user.firstName(), user.lastName(), java.sql.Timestamp.from(user.createdAt()));
            return Map.of("id", user.getId().value(), "email", user.email().value());
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered"); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @PostMapping("/login")
    @Transactional
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request == null || request.email() == null || request.password() == null || request.email().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        final String normalizedEmail;
        try { normalizedEmail=new Email(request.email()).value(); }
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");}
        var users = jdbc.query("SELECT id,email,password_hash,role,active FROM users WHERE email=?", (rs, n) -> new UserRow(UUID.fromString(rs.getString("id")), rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("active")), normalizedEmail);
        if (users.isEmpty() || !users.getFirst().active() || !passwords.matches(request.password(), users.getFirst().passwordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        UserRow user = users.getFirst();
        var mfa = jdbc.query("SELECT encrypted_secret,verified,backup_code_hashes::text FROM mfa_methods WHERE user_id=? FOR UPDATE", (rs,n) -> new MfaRow(rs.getString(1),rs.getBoolean(2),rs.getString(3)),user.id());
        if (!mfa.isEmpty() && mfa.getFirst().verified()) {
            boolean valid = Totp.verify(encryption.decrypt(mfa.getFirst().secret()),request.mfaCode(),Instant.now());
            if (!valid && request.mfaCode()!=null) {
                try {
                    List<String> codes=mapper.readValue(mfa.getFirst().backupCodes(),new TypeReference<>(){});
                    int used=-1; for(int i=0;i<codes.size();i++) if(passwords.matches(request.mfaCode(),codes.get(i))){used=i;break;}
                    if(used>=0){codes.remove(used);jdbc.update("UPDATE mfa_methods SET backup_code_hashes=?::jsonb WHERE user_id=?",mapper.writeValueAsString(codes),user.id());valid=true;}
                } catch(Exception e){throw new IllegalStateException("Could not validate MFA backup code",e);}
            }
            if (!valid) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"MFA_REQUIRED: submit a valid authenticator code or one-time backup code");
        }
        Instant now = Instant.now();
        jdbc.update("UPDATE users SET last_login=? WHERE id=?", java.sql.Timestamp.from(now), user.id());
        var claims = JwtClaimsSet.builder().issuer("artio").issuedAt(now).expiresAt(now.plusSeconds(3600)).subject(user.id().toString()).claim("email", user.email()).claim("role", user.role()).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", 3600, user.id(), user.email(), user.role());
    }

    public record RegisterRequest(String email, String password, String firstName, String lastName) { }
    public record LoginRequest(String email, String password, String mfaCode) { }
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UUID userId, String email, String role) { }
    private record UserRow(UUID id, String email, String passwordHash, String role, boolean active) { }
    private record MfaRow(String secret, boolean verified, String backupCodes) { }
}
