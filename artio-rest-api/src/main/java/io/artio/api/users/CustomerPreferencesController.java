package io.artio.api.users;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class CustomerPreferencesController {
    private static final List<String> SUPPORTED = List.of("en", "de", "fr", "it");
    private final JdbcTemplate jdbc;

    public CustomerPreferencesController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/v1/users/me/preferences")
    public CustomerPreferences preferences(JwtAuthenticationToken auth) {
        UUID userId = customerId(auth);
        String preferred = jdbc.query("SELECT preferred_locale FROM users WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, userId);
        StoreLanguages store = storeLanguages();
        return new CustomerPreferences(preferred, store.defaultLocale(), store.availableLanguages());
    }

    @PutMapping("/api/v1/users/me/preferences")
    public CustomerPreferences update(@RequestBody LocaleRequest request, JwtAuthenticationToken auth) {
        UUID userId = customerId(auth);
        String locale = normalize(request == null ? null : request.locale());
        StoreLanguages store = storeLanguages();
        if (locale == null || !store.availableLanguages().contains(locale))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a language enabled for this store");
        jdbc.update("UPDATE users SET preferred_locale=? WHERE id=?", locale, userId);
        return new CustomerPreferences(locale, store.defaultLocale(), store.availableLanguages());
    }

    private UUID customerId(JwtAuthenticationToken auth) {
        if (auth.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_CUSTOMER")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer preferences are only available to customer accounts");
        return UUID.fromString(auth.getToken().getSubject());
    }

    private StoreLanguages storeLanguages() {
        List<String> configured = jdbc.query("SELECT jsonb_array_elements_text(available_languages) FROM artio_installation_settings WHERE singleton_id=true",
                (rs, row) -> normalize(rs.getString(1)));
        String defaultLocale = jdbc.query("SELECT locale FROM artio_installation_settings WHERE singleton_id=true", rs -> rs.next() ? normalize(rs.getString(1)) : "en");
        List<String> available = configured.stream().filter(SUPPORTED::contains).distinct().toList();
        if (available.isEmpty()) available = List.of(defaultLocale != null && SUPPORTED.contains(defaultLocale) ? defaultLocale : "en");
        return new StoreLanguages(defaultLocale != null && available.contains(defaultLocale) ? defaultLocale : available.getFirst(), available);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String language = Locale.forLanguageTag(value.trim()).getLanguage().toLowerCase(Locale.ROOT);
        return SUPPORTED.contains(language) ? language : null;
    }

    public record LocaleRequest(String locale) { }
    public record CustomerPreferences(String preferredLocale, String defaultLocale, List<String> availableLanguages) { }
    private record StoreLanguages(String defaultLocale, List<String> availableLanguages) { }
}
