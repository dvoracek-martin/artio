package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.artio.extensions.commerce.PaymentProvider;
import io.artio.api.security.PrivilegedStepUpMfa;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

class PaymentMethodSettingsControllerTests {
    @Test
    void requiresSuperadminBeforeDiscoveringOrSavingPaymentSettings() {
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        PaymentMethodPreferences preferences = mock(PaymentMethodPreferences.class);
        var controller = new PaymentMethodSettingsController(providers, preferences, new PrivilegedStepUpMfa(true));

        assertThatThrownBy(() -> controller.update("CH", "CHF", new PaymentMethodSettingsController.MethodSettingsRequest(List.of()), auth("ADMIN")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(providers, never()).getIfAvailable();
        verify(preferences, never()).save(any(), any());
    }

    @Test
    void rejectsStaleSuperadminTokenWhenCurrentAuthorityIsOnlyAdmin() {
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        var controller = new PaymentMethodSettingsController(providers, mock(PaymentMethodPreferences.class), new PrivilegedStepUpMfa(true));

        assertThatThrownBy(() -> controller.update("CH", "CHF", new PaymentMethodSettingsController.MethodSettingsRequest(List.of()),
                auth("SUPERADMIN", "ADMIN", UUID.randomUUID())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(providers, never()).getIfAvailable();
    }

    @Test
    void rejectsIncompleteCatalogInsteadOfPartiallyChangingAvailability() {
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentMethodPreferences preferences = mock(PaymentMethodPreferences.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.discoverMethods(any())).thenReturn(new PaymentProvider.PaymentMethodDiscovery(List.of(
                method("cards", "card"), method("wallet", "twint")), List.of()));
        var controller = new PaymentMethodSettingsController(providers, preferences, new PrivilegedStepUpMfa(true));
        var incomplete = new PaymentMethodSettingsController.MethodSettingsRequest(List.of(
                new PaymentMethodPreferences.MethodPreferenceInput("card", "payments", UUID.randomUUID(), true, 0)));

        assertThatThrownBy(() -> controller.update("ch", "chf", incomplete, auth("SUPERADMIN")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(preferences, never()).save(any(), any());
    }

    @Test
    void savesOnlyTheCompleteDiscoveredProviderCatalogForNormalizedMarket() {
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        PaymentMethodPreferences preferences = mock(PaymentMethodPreferences.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        var card = method("payments.cards", "card");
        var wallet = method("payments.wallet", "twint");
        when(provider.discoverMethods(any())).thenAnswer(invocation -> {
            PaymentProvider.PaymentDiscoveryRequest request = invocation.getArgument(0);
            assertThat(request.country()).isEqualTo("CH");
            assertThat(request.currency()).isEqualTo("CHF");
            return new PaymentProvider.PaymentMethodDiscovery(List.of(card, wallet), List.of());
        });
        when(preferences.withPreferences(List.of(card, wallet))).thenReturn(List.of());
        var controller = new PaymentMethodSettingsController(providers, preferences, new PrivilegedStepUpMfa(true));
        UUID actor = UUID.randomUUID();
        var input = new PaymentMethodSettingsController.MethodSettingsRequest(List.of(
                new PaymentMethodPreferences.MethodPreferenceInput("twint", "payments.wallet", wallet.providerInstanceId(), false, 0),
                new PaymentMethodPreferences.MethodPreferenceInput("card", "payments.cards", card.providerInstanceId(), true, 1)));

        var result = controller.update("ch", "chf", input, auth("SUPERADMIN", actor));

        assertThat(result.country()).isEqualTo("CH");
        assertThat(result.currency()).isEqualTo("CHF");
        assertThat(result.canManage()).isTrue();
        verify(preferences).save(input.methods(), actor);
    }

    private static PaymentProvider.AvailablePaymentMethod method(String plugin, String methodId) {
        UUID instance = UUID.randomUUID();
        return new PaymentProvider.AvailablePaymentMethod(instance + ":" + methodId, methodId, methodId,
                plugin, instance, 1L, 1, true);
    }

    private static JwtAuthenticationToken auth(String role) { return auth(role, role, UUID.randomUUID()); }
    private static JwtAuthenticationToken auth(String role, UUID actor) { return auth(role, role, actor); }
    private static JwtAuthenticationToken auth(String claimRole, String authorityRole, UUID actor) {
        var now = java.time.Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject(actor.toString())
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("mfaVerified", true).claim("role", claimRole).build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + authorityRole)));
    }
}
