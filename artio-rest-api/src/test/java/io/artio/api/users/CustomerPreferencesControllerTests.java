package io.artio.api.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class CustomerPreferencesControllerTests {
    private static final UUID USER_ID = UUID.fromString("c4798cb2-d995-43bf-97bf-a1eb44d2917f");
    private static final String LANGUAGES_QUERY =
            "SELECT jsonb_array_elements_text(available_languages) FROM artio_installation_settings WHERE singleton_id=true";
    private static final String DEFAULT_LOCALE_QUERY =
            "SELECT locale FROM artio_installation_settings WHERE singleton_id=true";

    @Test
    void normalizesAndPersistsOnlyLanguagesEnabledForTheStore() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(LANGUAGES_QUERY), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
                .thenReturn(List.of("en", "fr"));
        when(jdbc.query(eq(DEFAULT_LOCALE_QUERY), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any()))
                .thenReturn("fr");
        var controller = new CustomerPreferencesController(jdbc);

        var preferences = controller.update(new CustomerPreferencesController.LocaleRequest("fr-CH"), customer());

        assertThat(preferences.preferredLocale()).isEqualTo("fr");
        assertThat(preferences.defaultLocale()).isEqualTo("fr");
        assertThat(preferences.availableLanguages()).containsExactly("en", "fr");
        verify(jdbc).update("UPDATE users SET preferred_locale=? WHERE id=?", "fr", USER_ID);
    }

    @Test
    void rejectsLanguagesThatAreNotEnabledForTheStore() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(LANGUAGES_QUERY), org.mockito.ArgumentMatchers.<RowMapper<String>>any()))
                .thenReturn(List.of("en"));
        when(jdbc.query(eq(DEFAULT_LOCALE_QUERY), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any()))
                .thenReturn("en");

        assertThatThrownBy(() -> new CustomerPreferencesController(jdbc)
                .update(new CustomerPreferencesController.LocaleRequest("fr"), customer()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Choose a language enabled for this store");
    }

    @Test
    void restrictsPreferencesToCustomerAccounts() {
        var auth = token(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThatThrownBy(() -> new CustomerPreferencesController(mock(JdbcTemplate.class))
                .preferences(auth))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only available to customer accounts");
    }

    private static JwtAuthenticationToken customer() {
        return token(List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private static JwtAuthenticationToken token(List<SimpleGrantedAuthority> authorities) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject(USER_ID.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
