package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.extensions.commerce.PaymentProvider;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;

class PaymentControllerMethodPreferencesTests {
    @Test
    void storefrontReceivesOnlyEnabledMethodsInMerchantOrderAndGetsOpaqueReferences() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        var card = method("payments.cards", "card", "Card");
        var twint = method("payments.wallet", "twint", "TWINT");
        when(provider.discoverMethods(any())).thenReturn(new PaymentProvider.PaymentMethodDiscovery(List.of(card, twint), List.of()));
        PaymentMethodPreferences preferences = mock(PaymentMethodPreferences.class);
        when(preferences.visibleInOrder(List.of(card, twint))).thenReturn(List.of(twint));
        var controller = new PaymentController(jdbc, mock(InventoryApplicationService.class), mock(OrderUpdatePublisher.class),
                providers, preferences, mock(CheckoutFailureCompensation.class), "", "http://localhost:4200");

        var result = controller.paymentMethods(new PaymentController.PaymentMethodDiscoveryRequest("ch", "chf", null));

        assertThat(result.methods()).hasSize(1);
        assertThat(result.methods().getFirst().displayName()).isEqualTo("TWINT");
        assertThat(result.methods().getFirst().reference()).matches("[0-9a-f-]{36}");
        verify(jdbc).update(anyString());
        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO payment_method_references"), any(Object[].class));
    }

    @Test
    void checkoutCannotUseAnOldReferenceAfterItsPaymentMethodIsHidden() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getBigDecimal(1)).thenReturn(new BigDecimal("10.00"));
            when(row.getString(2)).thenReturn("CHF");
            when(row.getString(3)).thenReturn("PENDING");
            when(row.getString(4)).thenReturn("CH");
            return List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0));
        });
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Boolean.class), any())).thenReturn(false);
        PaymentProvider provider = mock(PaymentProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<PaymentProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new PaymentProvider.PaymentCapabilities(true, false, false, false, false, false, Optional.of(Duration.ofMinutes(30))));
        var card = method("payments.cards", "card", "Card");
        when(provider.discoverMethods(any())).thenReturn(new PaymentProvider.PaymentMethodDiscovery(List.of(card), List.of()));
        PaymentMethodPreferences preferences = mock(PaymentMethodPreferences.class);
        when(preferences.visibleInOrder(List.of(card))).thenReturn(List.of());
        var controller = new PaymentController(jdbc, mock(InventoryApplicationService.class), mock(OrderUpdatePublisher.class),
                providers, preferences, mock(CheckoutFailureCompensation.class), "", "http://localhost:4200");
        Jwt token = Jwt.withTokenValue("customer-token").header("alg", "none").subject(customerId.toString()).build();

        assertThatThrownBy(() -> controller.checkoutSession(orderId,
                new PaymentController.PaymentSelection("old-reference"), new JwtAuthenticationToken(token)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static PaymentProvider.AvailablePaymentMethod method(String plugin, String methodId, String displayName) {
        UUID instance = UUID.randomUUID();
        return new PaymentProvider.AvailablePaymentMethod(instance + ":" + methodId, methodId, displayName,
                plugin, instance, 1L, 0, true);
    }
}
