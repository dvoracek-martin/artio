package io.artio.api.users;

import io.artio.extensions.commerce.NotificationTransport;
import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import io.artio.users.Email;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class AccountController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    private final JdbcTemplate jdbc;
    private final ObjectProvider<NotificationTransport> notificationTransport;
    private final Environment environment;
    private final String backofficeUrl;

    public AccountController(JdbcTemplate jdbc, ObjectProvider<NotificationTransport> notificationTransport,
            Environment environment, @Value("${artio.backoffice-url:http://localhost:4201}") String backofficeUrl) {
        this.jdbc = jdbc;
        this.notificationTransport = notificationTransport;
        this.environment = environment;
        this.backofficeUrl = backofficeUrl;
    }

    @PutMapping("/api/v1/users/me/profile")
    public UserController.UserView updateProfile(@RequestBody ProfileRequest request, JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        requireCurrentPassword(userId, request == null ? null : request.currentPassword());
        String firstName = requiredName(request.firstName(), "First name");
        String lastName = requiredName(request.lastName(), "Last name");
        jdbc.update("UPDATE users SET first_name=?,last_name=? WHERE id=?", firstName, lastName, userId);
        return find(userId);
    }

    @PutMapping("/api/v1/users/me/password")
    @Transactional
    public Map<String, String> updatePassword(@RequestBody PasswordRequest request, JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        requireCurrentPassword(userId, request == null ? null : request.currentPassword());
        if (request.newPassword() == null || request.newPassword().length() < 8 || request.newPassword().length() > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain between 8 and 72 characters");
        jdbc.update("UPDATE users SET password_hash=?,token_version=token_version+1 WHERE id=?",
                PASSWORDS.encode(request.newPassword()), userId);
        return Map.of("message", "Password updated. Sign in again with your new password.");
    }

    @PostMapping("/api/v1/users/me/email-change")
    @Transactional
    public EmailChangeResponse requestEmailChange(@RequestBody EmailChangeRequest request, JwtAuthenticationToken auth) {
        UUID userId = userId(auth);
        requireCurrentPassword(userId, request == null ? null : request.currentPassword());
        final String email;
        try { email = new Email(request.newEmail()).value(); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid email address"); }
        String currentEmail = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
        if (email.equals(currentEmail)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is already your current email address");
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE email=? AND id<>?)", Boolean.class, email, userId)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email address is already in use");

        NotificationTransport transport = notificationTransport.getIfAvailable();
        boolean developerMode = (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))
                || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("development")))
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"));
        if (transport == null && !developerMode)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email delivery is not configured. Your email address was not changed.");

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID tokenId = UUID.randomUUID();
        jdbc.update("UPDATE artio_email_change_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", userId);
        jdbc.update("INSERT INTO artio_email_change_tokens(id,user_id,new_email,token_sha256,expires_at) VALUES (?,?,?,?,now()+interval '30 minutes')",
                tokenId, userId, email, sha256(token));
        if (transport != null) {
            String link = backofficeUrl.replaceAll("/$", "") + "/?emailChangeToken=" + token;
            try {
                var result = transport.deliver(new OutboundMessage(tokenId.toString(), "email", email,
                        "Confirm your new Artio email address",
                        "A request was made to change the email address on your Artio account.\n\n"
                                + "Confirm the new address within 30 minutes:\n" + link
                                + "\n\nIf you did not request this change, ignore this message.",
                        Map.of("purpose", "account-email-change")));
                if (result == null || !result.accepted()) throw new IllegalStateException("Email delivery was not accepted");
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE artio_email_change_tokens SET used_at=now() WHERE id=?", tokenId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The confirmation email could not be sent. Your current email address is unchanged.", failure);
            }
        }
        return new EmailChangeResponse("Confirm the new email address before it becomes active.",
                developerMode && transport == null ? token : null, transport != null);
    }

    @PostMapping("/api/v1/auth/email-change/complete")
    @Transactional
    public Map<String, String> confirmEmailChange(@RequestBody EmailChangeCompletion request) {
        if (request == null || request.token() == null || request.token().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email confirmation token is required");
        var rows = jdbc.query("SELECT t.user_id,t.new_email FROM artio_email_change_tokens t JOIN users u ON u.id=t.user_id "
                        + "WHERE t.token_sha256=? AND t.used_at IS NULL AND t.expires_at>now() AND u.active=true FOR UPDATE OF t,u",
                (rs, row) -> new PendingEmail(rs.getObject("user_id", UUID.class), rs.getString("new_email")), sha256(request.token()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This email confirmation link is invalid or expired");
        PendingEmail pending = rows.getFirst();
        try {
            jdbc.update("UPDATE users SET email=?,email_verified=true,token_version=token_version+1 WHERE id=?",
                    pending.email(), pending.userId());
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email address is already in use");
        }
        jdbc.update("UPDATE artio_email_change_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", pending.userId());
        return Map.of("message", "Email address confirmed. Sign in again with your new email.");
    }

    private void requireCurrentPassword(UUID userId, String currentPassword) {
        if (currentPassword == null || currentPassword.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter your current password to confirm this change");
        var hashes = jdbc.query("SELECT password_hash FROM users WHERE id=? AND active=true", (rs, row) -> rs.getString(1), userId);
        if (hashes.isEmpty() || !PASSWORDS.matches(currentPassword, hashes.getFirst()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Your current password is incorrect");
    }

    private UserController.UserView find(UUID id) {
        return jdbc.query("SELECT id,email,first_name,last_name,role,permissions,email_verified,active,created_at,last_login FROM users WHERE id=?",
                (rs, row) -> UserController.view(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getString("role"), rs.getString("permissions"), rs.getBoolean("email_verified"),
                        rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_login") == null ? null : rs.getTimestamp("last_login").toInstant()), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private static UUID userId(JwtAuthenticationToken auth) { return UUID.fromString(auth.getToken().getSubject()); }
    private static String requiredName(String value, String label) {
        if (value == null || value.isBlank() || value.trim().length() > 255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must contain between 1 and 255 characters");
        return value.trim();
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ProfileRequest(String firstName, String lastName, String currentPassword) { }
    public record PasswordRequest(String currentPassword, String newPassword) { }
    public record EmailChangeRequest(String newEmail, String currentPassword) { }
    public record EmailChangeCompletion(String token) { }
    public record EmailChangeResponse(String message, String developmentToken, boolean emailDeliveryConfigured) { }
    private record PendingEmail(UUID userId, String email) { }
}
