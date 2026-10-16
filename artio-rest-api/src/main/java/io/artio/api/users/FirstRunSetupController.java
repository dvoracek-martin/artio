package io.artio.api.users;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.users.Email;
import io.artio.api.distributions.DistributionPluginProvisioningService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.net.URI;
import java.util.List;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/setup")
public class FirstRunSetupController {
    private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();
    private static final TypeReference<Map<String, String>> DRAFT_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final DistributionPluginProvisioningService provisioning;

    public FirstRunSetupController(JdbcTemplate jdbc, ObjectMapper mapper, Environment environment, DistributionPluginProvisioningService provisioning) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.environment = environment;
        this.provisioning = provisioning;
    }

    @GetMapping("/status")
    public SetupStatus status(@RequestHeader(value = "X-Artio-Setup-Token", required = false) String token) {
        boolean required = setupRequired();
        return new SetupStatus(required, !required, false, developmentMode(), Map.of());
    }

    @PostMapping("/resume")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SetupStatus resume(@RequestHeader("X-Artio-Setup-Token") String token) {
        authorize(token);
        boolean required = setupRequired();
        return new SetupStatus(required, !required, true, developmentMode(), required ? loadDraft() : Map.of());
    }

    @PostMapping("/progress")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SetupStatus progress(@RequestHeader("X-Artio-Setup-Token") String token, @RequestBody SetupProgress progress) {
        authorize(token);
        if (!setupRequired()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Initial setup is already complete");
        if (progress == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setup progress is required");
        validateRegion(progress.countryCode(), progress.currencyCode(), progress.locale(), progress.timezone());
        Map<String, String> draft = Map.ofEntries(
                Map.entry("countryCode", progress.countryCode().toUpperCase(Locale.ROOT)),
                Map.entry("currencyCode", progress.currencyCode().toUpperCase(Locale.ROOT)),
                Map.entry("locale", Locale.forLanguageTag(progress.locale()).toLanguageTag()),
                Map.entry("timezone", ZoneId.of(progress.timezone()).getId()),
                Map.entry("storeName", requiredText(progress.storeName() == null ? "Artio Store" : progress.storeName(), "Store name")),
                Map.entry("availableLanguages", normalizeLanguages(progress.availableLanguages(), progress.locale())),
                Map.entry("externalBaseUrl", validateExternalUrl(progress.externalBaseUrl())),
                Map.entry("distributionId", "switzerland-v1"),
                Map.entry("email", new Email(progress.email()).value()),
                Map.entry("firstName", requiredText(progress.firstName(), "First name")),
                Map.entry("lastName", requiredText(progress.lastName(), "Last name")));
        try {
            jdbc.update("UPDATE artio_first_run_setup SET draft=?::jsonb, updated_at=now() WHERE singleton_id=true AND completed_at IS NULL",
                    mapper.writeValueAsString(draft));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save setup progress", failure);
        }
        return new SetupStatus(true, false, true, developmentMode(), draft);
    }

    @PostMapping("/complete")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SetupCompleted complete(@RequestHeader("X-Artio-Setup-Token") String token, @RequestBody SetupCompletion completion) {
        if (!setupRequired()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Initial setup is already complete");
        authorize(token);
        if (completion == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An administrator password is required");
        var states = jdbc.query("SELECT setup_token_sha256,draft::text,completed_at FROM artio_first_run_setup WHERE singleton_id=true FOR UPDATE",
                (rs, row) -> new SetupRow(rs.getString(1), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()));
        if (states.isEmpty() || states.getFirst().completedAt() != null || administratorExists()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Initial setup is already complete");
        }
        Map<String, String> draft;
        try {
            draft = mapper.readValue(states.getFirst().draft(), DRAFT_TYPE);
        } catch (Exception failure) {
            throw new IllegalStateException("Saved setup progress is invalid", failure);
        }
        if (draft.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Save setup progress before completing installation");
        if (completion.password() == null || completion.password().length() < 16 || completion.password().length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Administrator password must contain between 16 and 72 characters");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,permissions,created_at) VALUES (?,?,?,?,?,'SUPERADMIN','*',?)",
                id, draft.get("email"), PASSWORDS.encode(completion.password()), draft.get("firstName"), draft.get("lastName"), java.sql.Timestamp.from(Instant.now()));
        try {
            jdbc.update("INSERT INTO artio_installation_settings(singleton_id,store_name,country_code,currency_code,locale,available_languages,timezone,external_base_url,distribution_id) VALUES (true,?,?,?, ?,?::jsonb,?,?,?) "
                            + "ON CONFLICT (singleton_id) DO UPDATE SET store_name=EXCLUDED.store_name,country_code=EXCLUDED.country_code,currency_code=EXCLUDED.currency_code,locale=EXCLUDED.locale,available_languages=EXCLUDED.available_languages,timezone=EXCLUDED.timezone,external_base_url=EXCLUDED.external_base_url,distribution_id=EXCLUDED.distribution_id,updated_at=now()",
                    draft.get("storeName"), draft.get("countryCode"), draft.get("currencyCode"), draft.get("locale"),
                    mapper.writeValueAsString(List.of(draft.get("availableLanguages").split(","))), draft.get("timezone"),
                    draft.get("externalBaseUrl"), draft.get("distributionId"));
        } catch (Exception failure) { throw new IllegalStateException("Could not persist installation settings", failure); }
        provisioning.initialize(draft.get("distributionId"));
        jdbc.update("UPDATE artio_first_run_setup SET completed_at=now(),draft='{}'::jsonb,failed_attempts=0,locked_until=NULL,updated_at=now() WHERE singleton_id=true");
        return new SetupCompleted(true, "SUPERADMIN", draft.get("countryCode"), draft.get("currencyCode"), draft.get("locale"), draft.get("timezone"));
    }

    private void authorize(String token) {
        if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Setup authorization failed");
        if (!validToken(token, true)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Setup authorization failed");
    }

    private boolean validToken(String token, boolean countFailure) {
        var rows = jdbc.query("SELECT setup_token_sha256,failed_attempts,locked_until,completed_at FROM artio_first_run_setup WHERE singleton_id=true" + (countFailure ? " FOR UPDATE" : ""),
                (rs, row) -> new TokenState(rs.getString(1), rs.getInt(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) != null));
        if (rows.isEmpty() || rows.getFirst().completed()) return false;
        TokenState state = rows.getFirst();
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now())) {
            if (countFailure) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Setup is temporarily locked");
            return false;
        }
        byte[] expected = state.hash().trim().getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = FirstRunSetupConfiguration.sha256(token).getBytes(StandardCharsets.US_ASCII);
        boolean valid = MessageDigest.isEqual(expected, supplied);
        if (!valid && countFailure) {
            int attempts = state.failedAttempts() + 1;
            jdbc.update("UPDATE artio_first_run_setup SET failed_attempts=?,locked_until=?,updated_at=now() WHERE singleton_id=true",
                    attempts % 5 == 0 ? 0 : attempts,
                    attempts % 5 == 0 ? java.sql.Timestamp.from(Instant.now().plusSeconds(900)) : null);
        }
        return valid;
    }

    private boolean setupRequired() {
        if (administratorExists()) return false;
        jdbc.update("UPDATE artio_first_run_setup SET completed_at=NULL,draft='{}'::jsonb,failed_attempts=0,locked_until=NULL,updated_at=now() WHERE singleton_id=true AND completed_at IS NOT NULL");
        return true;
    }
    private boolean administratorExists() { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM users WHERE role IN ('ADMIN','SUPERADMIN'))", Boolean.class)); }
    private Map<String, String> loadDraft() {
        String draft = jdbc.queryForObject("SELECT draft::text FROM artio_first_run_setup WHERE singleton_id=true", String.class);
        try { return mapper.readValue(draft, DRAFT_TYPE); }
        catch (Exception failure) { throw new IllegalStateException("Saved setup progress is invalid", failure); }
    }
    private boolean developmentMode() {
        return (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"))
                || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("development")))
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("production"));
    }
    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        return value.trim();
    }
    private static String normalizeLanguages(String raw, String locale) {
        String value = raw == null || raw.isBlank() ? locale : raw;
        List<String> tags = java.util.Arrays.stream(value.split(","))
                .map(String::trim).map(Locale::forLanguageTag).map(Locale::toLanguageTag).distinct().toList();
        if (tags.isEmpty() || tags.size() > 32 || tags.stream().anyMatch(tag -> tag.equals("und"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose between 1 and 32 valid supported languages");
        }
        return String.join(",", tags);
    }
    private static String validateExternalUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.trim());
            boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && (uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1"));
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !("https".equalsIgnoreCase(uri.getScheme()) || localHttp)) throw new IllegalArgumentException();
            return uri.toString();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External URL must use HTTPS (HTTP is allowed for localhost development)");
        }
    }
    private static void validateRegion(String country, String currency, String locale, String timezone) {
        try {
            if (country == null || country.length() != 2 || !Locale.getISOCountries(java.util.Locale.IsoCountryCode.PART1_ALPHA2).contains(country.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException();
            if (currency == null || currency.length() != 3 || Currency.getInstance(currency.toUpperCase(Locale.ROOT)) == null) throw new IllegalArgumentException();
            if (locale == null || Locale.forLanguageTag(locale).getLanguage().isBlank()) throw new IllegalArgumentException();
            ZoneId.of(timezone);
        } catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid country, currency, locale and time zone", failure); }
    }

    public record SetupStatus(boolean required, boolean complete, boolean tokenAccepted, boolean developerMode, Map<String, String> draft) { }
    public record SetupProgress(String countryCode, String currencyCode, String locale, String timezone, String email,
                                String firstName, String lastName, String storeName, String availableLanguages, String externalBaseUrl) {
        public SetupProgress(String countryCode, String currencyCode, String locale, String timezone,
                             String email, String firstName, String lastName) {
            this(countryCode, currencyCode, locale, timezone, email, firstName, lastName, "Artio Store", locale, null);
        }
    }
    public record SetupCompletion(String password) { }
    public record SetupCompleted(boolean complete, String administratorRole, String countryCode, String currencyCode, String locale, String timezone) { }
    private record SetupRow(String hash, String draft, Instant completedAt) { }
    private record TokenState(String hash, int failedAttempts, Instant lockedUntil, boolean completed) { }
}
