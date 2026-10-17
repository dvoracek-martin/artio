package io.artio.api.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Enforces fresh MFA for privileged changes while honoring the explicit local-dev exemption. */
@Component
public final class PrivilegedStepUpMfa {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private final boolean required;

    @Autowired
    public PrivilegedStepUpMfa(
            @Value("${artio.security.mfa-required-for-privileged-users:true}") boolean required,
            Environment environment) {
        this(required || java.util.Arrays.asList(environment.getActiveProfiles()).contains("production"));
    }

    public PrivilegedStepUpMfa(boolean required) {
        this.required = required;
    }

    public void require(JwtAuthenticationToken actor) {
        if (!required) return;
        Instant issuedAt = actor == null ? null : actor.getToken().getIssuedAt();
        Instant now = Instant.now();
        if (actor == null || !Boolean.TRUE.equals(actor.getToken().getClaimAsBoolean("mfaVerified"))
                || issuedAt == null || issuedAt.isBefore(now.minus(MAX_AGE)) || issuedAt.isAfter(now.plusSeconds(30))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Recent multi-factor authentication is required");
        }
    }
}
