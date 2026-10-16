package io.artio.api.users;

import io.artio.users.Email;
import io.artio.extensions.commerce.NotificationTransport;
import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    private static final String GENERIC_MESSAGE = "If an administrator account matches that email, password recovery instructions have been sent.";
    private static final String DEVELOPMENT_EMAIL_UNAVAILABLE = "Email could not be sent because email delivery is not configured in this developer instance. Use the local reset token below.";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<NotificationTransport> notificationTransport;
    private final Environment environment;
    private final String backofficeUrl;

    public PasswordResetController(JdbcTemplate jdbc, ObjectProvider<NotificationTransport> notificationTransport,
            Environment environment, @Value("${artio.backoffice-url:http://localhost:4201}") String backofficeUrl) {
        this.jdbc = jdbc;
        this.notificationTransport = notificationTransport;
        this.environment = environment;
        this.backofficeUrl = backofficeUrl;
    }

    @PostMapping("/request")
    @Transactional
    public ResetRequestResponse request(@RequestBody ResetRequest request) {
        NotificationTransport transport = notificationTransport.getIfAvailable();
        boolean developerMode = (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))
                || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("development")))
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"));
        if (transport == null && !developerMode) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Password recovery email is not configured. Contact your system administrator.");
        }
        String email;
        try { email = request == null ? null : new Email(request.email()).value(); }
        catch (RuntimeException invalid) {
            return new ResetRequestResponse(transport == null ? DEVELOPMENT_EMAIL_UNAVAILABLE : GENERIC_MESSAGE, null,
                    transport != null);
        }

        var accounts = jdbc.query("SELECT id,email FROM users WHERE email=? AND role IN ('ADMIN','SUPERADMIN') AND active=true",
                (rs, row) -> new AdminAccount(rs.getObject("id", UUID.class), rs.getString("email")), email);
        if (accounts.isEmpty()) return new ResetRequestResponse(transport == null ? DEVELOPMENT_EMAIL_UNAVAILABLE : GENERIC_MESSAGE,
                null, transport != null);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID tokenId = UUID.randomUUID();
        jdbc.update("UPDATE artio_password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL",
                accounts.getFirst().id());
        jdbc.update("INSERT INTO artio_password_reset_tokens(id,user_id,token_sha256,expires_at) VALUES (?,?,?,now()+interval '30 minutes')",
                tokenId, accounts.getFirst().id(), sha256(token));

        if (transport != null) {
            String link = backofficeUrl.replaceAll("/$", "") + "/?resetToken=" + token;
            try {
                var result = transport.deliver(new OutboundMessage(tokenId.toString(), "email", accounts.getFirst().email(),
                        "Reset your Artio administrator password",
                        "A password reset was requested for your Artio administrator account.\n\n"
                                + "Use this link within 30 minutes to choose a new password:\n" + link
                                + "\n\nIf you did not request this, you can ignore this email.",
                        Map.of("purpose", "administrator-password-reset")));
                if (result == null || !result.accepted()) throw new IllegalStateException("Notification transport did not accept the reset email");
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE artio_password_reset_tokens SET used_at=now() WHERE id=?", tokenId);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Password recovery email could not be sent. Please try again later.", failure);
            }
        }
        return new ResetRequestResponse(transport == null ? DEVELOPMENT_EMAIL_UNAVAILABLE : GENERIC_MESSAGE,
                developerMode && transport == null ? token : null, transport != null);
    }

    @PostMapping("/complete")
    @Transactional
    public Map<String, String> complete(@RequestBody ResetCompletion request) {
        if (request == null || request.token() == null || request.password() == null
                || request.password().length() < 8 || request.password().length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use a password between 8 and 72 characters and a valid recovery link");
        }
        var rows = jdbc.query("SELECT t.user_id FROM artio_password_reset_tokens t JOIN users u ON u.id=t.user_id "
                        + "WHERE t.token_sha256=? AND t.used_at IS NULL AND t.expires_at>now() AND u.active=true "
                        + "AND u.role IN ('ADMIN','SUPERADMIN') FOR UPDATE OF t,u",
                (rs, row) -> rs.getObject("user_id", UUID.class), sha256(request.token()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This password recovery link is invalid or expired");
        UUID userId = rows.getFirst();
        jdbc.update("UPDATE users SET password_hash=?,token_version=token_version+1 WHERE id=?",
                PASSWORDS.encode(request.password()), userId);
        jdbc.update("UPDATE artio_password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", userId);
        return Map.of("message", "Password updated. Sign in with your new password.");
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record ResetRequest(String email) { }
    public record ResetCompletion(String token, String password) { }
    public record ResetRequestResponse(String message, String developmentToken, boolean emailDeliveryConfigured) { }
    private record AdminAccount(UUID id, String email) { }
}
