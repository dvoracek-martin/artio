package io.artio.api.enterprise;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Installation-level settings; every operation is restricted to the system owner role. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin/system/settings")
@Transactional
public class SystemSettingsController {
    private static final TypeReference<List<String>> LANGUAGES = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SystemSettingsController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public SystemSettingsView get(JwtAuthenticationToken actor) {
        requireOwner(actor);
        var rows = jdbc.query("SELECT store_name,country_code,currency_code,locale,available_languages::text,timezone,external_base_url,distribution_id,updated_at FROM artio_installation_settings WHERE singleton_id=true",
                (rs, row) -> {
                    try {
                        return new SystemSettingsView(rs.getString("store_name"), rs.getString("country_code").trim(),
                                rs.getString("currency_code").trim(), rs.getString("locale"),
                                mapper.readValue(rs.getString("available_languages"), LANGUAGES), rs.getString("timezone"),
                                rs.getString("external_base_url"), rs.getString("distribution_id"),
                                rs.getTimestamp("updated_at").toInstant());
                    } catch (Exception failure) {
                        throw new IllegalStateException("Could not read installation settings", failure);
                    }
                });
        return rows.isEmpty() ? new SystemSettingsView("Artio Store", "CH", "CHF", "de-CH",
                List.of("de", "fr", "it", "en"), "Europe/Zurich", null, "switzerland-v1", Instant.EPOCH) : rows.getFirst();
    }

    @PutMapping
    public SystemSettingsView update(@RequestBody SystemSettingsUpdate request, JwtAuthenticationToken actor) {
        requireOwner(actor);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Settings are required");
        String storeName = request.storeName() == null ? "" : request.storeName().trim();
        if (storeName.isEmpty() || storeName.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Store name must contain 1 to 120 characters");
        String country = validateCountry(request.countryCode());
        String currency = validateCurrency(request.currencyCode());
        String locale = validateLocale(request.locale());
        String timezone = validateTimezone(request.timezone());
        List<String> languages = validateLanguages(request.availableLanguages());
        String externalUrl = validateExternalUrl(request.externalBaseUrl());
        String distributionId = "switzerland-v1";
        try {
            jdbc.update("INSERT INTO artio_installation_settings(singleton_id,store_name,country_code,currency_code,locale,available_languages,timezone,external_base_url,distribution_id,updated_at) VALUES (true,?,?,?, ?,?::jsonb,?,?,?,now()) "
                            + "ON CONFLICT(singleton_id) DO UPDATE SET store_name=EXCLUDED.store_name,country_code=EXCLUDED.country_code,currency_code=EXCLUDED.currency_code,locale=EXCLUDED.locale,available_languages=EXCLUDED.available_languages,timezone=EXCLUDED.timezone,external_base_url=EXCLUDED.external_base_url,distribution_id=EXCLUDED.distribution_id,updated_at=now()",
                    storeName, country, currency, locale, mapper.writeValueAsString(languages), timezone, externalUrl, distributionId);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not save installation settings", failure);
        }
        return get(actor);
    }

    @GetMapping("/audit")
    @Transactional(readOnly = true)
    public List<SettingsAuditEntry> audit(JwtAuthenticationToken actor) {
        requireOwner(actor);
        return jdbc.query("SELECT id,user_id,action,path,status,occurred_at FROM audit_logs WHERE path LIKE '%/admin/system/settings%' ORDER BY occurred_at DESC LIMIT 100",
                (rs, row) -> new SettingsAuditEntry(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("action"), rs.getString("path"), rs.getInt("status"), rs.getTimestamp("occurred_at").toInstant()));
    }

    private static void requireOwner(JwtAuthenticationToken actor) {
        if (actor == null || !"SUPERADMIN".equals(actor.getToken().getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the system owner can access installation settings");
        }
    }

    private static String validateCountry(String raw) {
        if (raw == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Country code is required");
        String value = raw.toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{2}") || !SetHolder.ISO_COUNTRIES.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Country must be an ISO 3166-1 alpha-2 code");
        return value;
    }

    private static String validateCurrency(String raw) {
        try { return Currency.getInstance(raw.toUpperCase(Locale.ROOT)).getCurrencyCode(); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency must be a valid ISO 4217 code"); }
    }

    private static String validateLocale(String raw) {
        Locale locale = raw == null ? Locale.ROOT : Locale.forLanguageTag(raw);
        if (locale.getLanguage().isBlank() || locale.toLanguageTag().equals("und")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Locale is invalid");
        return locale.toLanguageTag();
    }

    private static String validateTimezone(String raw) {
        try { return ZoneId.of(raw).getId(); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Time zone is invalid"); }
    }

    private static List<String> validateLanguages(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > 32) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose between 1 and 32 supported languages");
        List<String> normalized = raw.stream().map(SystemSettingsController::validateLocale).toList();
        if (new HashSet<>(normalized).size() != normalized.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supported languages must be unique");
        return normalized;
    }

    private static String validateExternalUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())))) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "External URL must use HTTPS (HTTP is allowed for localhost development)");
        }
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    public record SystemSettingsUpdate(String storeName, String countryCode, String currencyCode, String locale,
                                       List<String> availableLanguages, String timezone, String externalBaseUrl) { }
    public record SystemSettingsView(String storeName, String countryCode, String currencyCode, String locale,
                                     List<String> availableLanguages, String timezone, String externalBaseUrl,
                                     String distributionId, Instant updatedAt) { }
    public record SettingsAuditEntry(UUID id, UUID userId, String action, String path, int status, Instant occurredAt) { }

    private static final class SetHolder {
        private static final java.util.Set<String> ISO_COUNTRIES = java.util.Set.of(Locale.getISOCountries());
    }
}
