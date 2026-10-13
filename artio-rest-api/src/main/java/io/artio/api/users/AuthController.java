package io.artio.api.users;

import io.artio.users.Email;
import io.artio.users.User;
import io.artio.users.UserId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    public AuthController(JdbcTemplate jdbc, JwtEncoder jwtEncoder) { this.jdbc = jdbc; this.jwtEncoder = jwtEncoder; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        if (request == null || request.password() == null || request.password().length() < 12 || request.password().length() > 72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain between 12 and 72 characters");
        try {
            User user = User.registerCustomer(new UserId(UUID.randomUUID()), new Email(request.email()), passwords.encode(request.password()), request.firstName(), request.lastName());
            jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'CUSTOMER',?)", user.getId().value(), user.email().value(), user.passwordHash(), user.firstName(), user.lastName(), java.sql.Timestamp.from(user.createdAt()));
            return Map.of("id", user.getId().value(), "email", user.email().value());
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered"); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request == null || request.email() == null || request.password() == null || request.email().isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        final String normalizedEmail;
        try { normalizedEmail=new Email(request.email()).value(); }
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");}
        var users = jdbc.query("SELECT id,email,password_hash,role,active FROM users WHERE email=?", (rs, n) -> new UserRow(UUID.fromString(rs.getString("id")), rs.getString("email"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("active")), normalizedEmail);
        if (users.isEmpty() || !users.getFirst().active() || !passwords.matches(request.password(), users.getFirst().passwordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        UserRow user = users.getFirst();
        Instant now = Instant.now();
        jdbc.update("UPDATE users SET last_login=? WHERE id=?", java.sql.Timestamp.from(now), user.id());
        var claims = JwtClaimsSet.builder().issuer("artio").issuedAt(now).expiresAt(now.plusSeconds(3600)).subject(user.id().toString()).claim("email", user.email()).claim("role", user.role()).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", 3600, user.id(), user.email(), user.role());
    }

    public record RegisterRequest(String email, String password, String firstName, String lastName) { }
    public record LoginRequest(String email, String password) { }
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UUID userId, String email, String role) { }
    private record UserRow(UUID id, String email, String passwordHash, String role, boolean active) { }
}
