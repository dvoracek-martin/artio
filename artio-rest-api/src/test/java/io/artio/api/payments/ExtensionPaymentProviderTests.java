package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.commerce.PaymentProvider.CheckoutRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ExtensionPaymentProviderTests {
    private final ManagedExtensionInvoker invoker = mock(ManagedExtensionInvoker.class);
    private final ExtensionPaymentProvider provider = new ExtensionPaymentProvider(invoker, new ObjectMapper(), "payments.example");

    @Test
    void checkoutUsesStablePluginIdempotencyKeyAndMapsProviderResponse() throws Exception {
        var request = new CheckoutRequest("order-123", "artio-order-order-123", new BigDecimal("12.50"), "CHF",
                "https://shop.test/success", "https://shop.test/cancel", Map.of("market", "CH"));
        String stableRuntimeKey = UUID.nameUUIDFromBytes("artio:createCheckout:artio-order-order-123".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        when(invoker.invoke(eq("payments.example"), eq("payment.provider.v1"), eq("createCheckout"), eq(stableRuntimeKey), eq(request)))
                .thenReturn(new ObjectMapper().readTree("{\"providerReference\":\"session-123\",\"checkoutUrl\":\"https://pay.test/session-123\",\"state\":\"PENDING\"}"));

        var result = provider.createCheckout(request);

        assertThat(result.providerReference()).isEqualTo("session-123");
        assertThat(result.checkoutUrl()).isEqualTo("https://pay.test/session-123");
        verify(invoker).invoke("payments.example", "payment.provider.v1", "createCheckout", stableRuntimeKey, request);
    }

    @Test
    void exposesExplicitCapabilitiesAndOptionalExpiry() throws Exception {
        when(invoker.invoke(eq("payments.example"), eq("payment.provider.v1"), eq("capabilities"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ObjectMapper().readTree("{\"hostedCheckout\":true,\"authorization\":false,\"capture\":false,\"cancellation\":true,\"refunds\":true,\"webhooks\":true,\"maximumCheckoutExpirySeconds\":1800}"));

        var capabilities = provider.capabilities();

        assertThat(capabilities.hostedCheckout()).isTrue();
        assertThat(capabilities.refunds()).isTrue();
        assertThat(capabilities.maximumCheckoutExpiry()).contains(java.time.Duration.ofSeconds(1800));
    }

    @Test
    void forwardsOnlyProviderHeaderBagAndOriginalWebhookBody() throws Exception {
        byte[] body = "{\"id\":\"evt-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(invoker.invoke(eq("payments.example"), eq("payment.provider.v1"), eq("verifyWebhook"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ObjectMapper().readTree("{\"providerEventId\":\"evt-1\",\"eventType\":\"test\",\"providerReference\":\"ref-1\",\"state\":\"PENDING\",\"verified\":true}"));

        var result = provider.verifyWebhook(Map.of("paypal-transmission-id", "tx-1", "paypal-transmission-sig", "sig-1"), body);

        assertThat(result.verified()).isTrue();
        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(invoker).invoke(eq("payments.example"), eq("payment.provider.v1"), eq("verifyWebhook"),
                org.mockito.ArgumentMatchers.anyString(), payload.capture());
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map.get("headers")).isEqualTo(Map.of("paypal-transmission-id", "tx-1", "paypal-transmission-sig", "sig-1"));
        assertThat(map.get("rawBodyBase64")).isEqualTo(Base64.getEncoder().encodeToString(body));
    }
}
