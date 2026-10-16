package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.PaymentProvider.CheckoutRequest;
import io.artio.extensions.commerce.PaymentProvider;
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
    void translatesOnlyProviderStatusesThatGuaranteeNoCheckoutWasCreated() {
        var request = new CheckoutRequest("order-123", "artio-order-order-123", new BigDecimal("12.50"), "CHF",
                "https://shop.test/success", "https://shop.test/cancel", Map.of());
        when(invoker.invoke(eq("payments.example"), eq("payment.provider.v1"), eq("createCheckout"),
                org.mockito.ArgumentMatchers.anyString(), eq(request)))
                .thenThrow(new ExtensionRuntimePort.ExtensionCallException(422));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.createCheckout(request))
                .isInstanceOf(io.artio.extensions.commerce.PaymentProvider.CheckoutRejectedException.class)
                .hasMessage("Payment provider definitively rejected checkout");
    }

    @Test
    void preservesRateLimitAsAmbiguousRetryableFailure() {
        var request = new CheckoutRequest("order-123", "artio-order-order-123", new BigDecimal("12.50"), "CHF",
                "https://shop.test/success", "https://shop.test/cancel", Map.of());
        when(invoker.invoke(eq("payments.example"), eq("payment.provider.v1"), eq("createCheckout"),
                org.mockito.ArgumentMatchers.anyString(), eq(request)))
                .thenThrow(new ExtensionRuntimePort.ExtensionCallException(429));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.createCheckout(request))
                .isInstanceOf(ExtensionRuntimePort.ExtensionCallException.class)
                .extracting("statusCode").isEqualTo(429);
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
                .thenReturn(new ObjectMapper().readTree("{\"providerEventId\":\"evt-1\",\"eventType\":\"test\",\"providerReference\":\"ref-1\",\"transactionReference\":\"pi-1\",\"state\":\"CAPTURED\",\"amount\":\"12.50\",\"currency\":\"CHF\",\"verified\":true}"));

        var result = provider.verifyWebhook(Map.of("paypal-transmission-id", "tx-1", "paypal-transmission-sig", "sig-1",
                "x-signature", "algorithm=SHA256withECDSA, keyId=42, signature=provider-signature"), body);

        assertThat(result.verified()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("12.50");
        assertThat(result.currency()).isEqualTo("CHF");
        assertThat(result.transactionReference()).isEqualTo("pi-1");
        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(invoker).invoke(eq("payments.example"), eq("payment.provider.v1"), eq("verifyWebhook"),
                org.mockito.ArgumentMatchers.anyString(), payload.capture());
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) payload.getValue();
        assertThat(map.get("headers")).isEqualTo(Map.of("paypal-transmission-id", "tx-1", "paypal-transmission-sig", "sig-1",
                "x-signature", "algorithm=SHA256withECDSA, keyId=42, signature=provider-signature"));
        assertThat(map.get("rawBodyBase64")).isEqualTo(Base64.getEncoder().encodeToString(body));
    }

    @Test
    void capabilityRoutedCheckoutReturnsItsPersistableHistoricalOwner() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("country", "CH", "currency", "CHF");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 42L, 3, "a".repeat(64), scope);
        when(routed.invoke(eq("payment.provider.v1"), eq("createCheckout"), eq(scope), eq(null), eq(true),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"providerReference\":\"session-v2\",\"checkoutUrl\":\"https://pay.test/session-v2\",\"state\":\"PENDING\"}"), owner));
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "payments.compat", "none");
        var request = new CheckoutRequest("order-123", "artio-order-order-123", new BigDecimal("12.50"), "CHF",
                "https://shop.test/success", "https://shop.test/cancel", Map.of("country", "CH"));

        var result = adapter.createCheckoutOwned(request);

        assertThat(result.result().providerReference()).isEqualTo("session-v2");
        assertThat(result.owner().instanceId()).isEqualTo(instanceId);
        assertThat(result.owner().releaseId()).isEqualTo(42L);
        assertThat(result.owner().configurationGeneration()).isEqualTo(3);
        assertThat(result.owner().externalAccountFingerprint()).isEqualTo("a".repeat(64));
        assertThat(result.owner().scope()).isEqualTo(scope);
    }

    @Test
    void settlementUsesTheOriginalInstanceAndScope() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("country", "CH");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 42L, 3, "b".repeat(64), scope);
        when(routed.invoke(eq("payment.provider.v1"), eq("capture"), eq(scope), eq(instanceId), eq(false),
                eq(UUID.nameUUIDFromBytes("artio:capture:payment-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"providerReference\":\"session-v2\",\"state\":\"CAPTURED\",\"amount\":\"12.50\",\"currency\":\"CHF\"}"), owner));
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "payments.compat", "none");
        var providerOwner = new PaymentProvider.ProviderOwner(instanceId, 42L, 3, "b".repeat(64), scope);

        var result = adapter.capture(providerOwner, "session-v2", "payment-1");

        assertThat(result.result().state()).isEqualTo(PaymentProvider.PaymentState.CAPTURED);
        assertThat(result.owner().instanceId()).isEqualTo(instanceId);
        verify(routed).invoke(eq("payment.provider.v1"), eq("capture"), eq(scope), eq(instanceId), eq(false),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void webhookVerificationUsesItsExplicitBindingOwner() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("country", "CH");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 42L, 3, "e".repeat(64), scope);
        when(routed.invoke(eq("payment.provider.v1"), eq("verifyWebhook"), eq(scope), eq(instanceId), eq(false),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"providerEventId\":\"evt-9\",\"eventType\":\"payment.captured\",\"providerReference\":\"session-9\",\"state\":\"CAPTURED\",\"amount\":\"12.50\",\"currency\":\"CHF\",\"verified\":true}"), owner));
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "payments.compat", "none");
        var providerOwner = new PaymentProvider.ProviderOwner(instanceId, 42L, 3, "e".repeat(64), scope);
        byte[] body = "{\"id\":\"evt-9\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        var result = adapter.verifyWebhook(providerOwner, Map.of("stripe-signature", "signed"), body, "verify-event-9");

        assertThat(result.result().verified()).isTrue();
        assertThat(result.result().providerReference()).isEqualTo("session-9");
        assertThat(result.owner().instanceId()).isEqualTo(instanceId);
        verify(routed).invoke(eq("payment.provider.v1"), eq("verifyWebhook"), eq(scope), eq(instanceId), eq(false),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void v2BindingsEnablePaymentsWithoutTheLegacyEnvironmentSelector() {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        when(routed.hasEnabledBinding("payment.provider.v1", "createCheckout")).thenReturn(true);
        when(routed.hasEnabledBinding("payment.provider.v1", "capture")).thenReturn(true);
        when(routed.hasEnabledBinding("payment.provider.v1", "cancel")).thenReturn(true);
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "", "none");

        var capabilities = adapter.capabilities();

        assertThat(capabilities.hostedCheckout()).isTrue();
        assertThat(capabilities.capture()).isTrue();
        assertThat(capabilities.cancellation()).isTrue();
        assertThat(capabilities.refunds()).isFalse();
    }

    @Test
    void aggregatesAndNamespacesMethodsByProviderInstance() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(routed.aggregateRead(eq("payment.provider.v1"), eq("listMethods"), eq(Map.of("country", "CH", "currency", "CHF")),
                org.mockito.ArgumentMatchers.any())).thenReturn(new CommerceCapabilityInvoker.AggregateInvocation(
                java.util.List.of(
                        new CommerceCapabilityInvoker.AggregateProviderResult(first, "payments.stripe", 4, 2,
                                new ObjectMapper().readTree("{\"methods\":[{\"methodId\":\"card\",\"displayName\":\"Card\",\"countries\":[\"CH\"],\"currencies\":[\"CHF\"],\"hostedCheckout\":true}] }"), null),
                        new CommerceCapabilityInvoker.AggregateProviderResult(second, "payments.paypal", 8, 1,
                                new ObjectMapper().readTree("{\"methods\":[{\"methodId\":\"paypal\",\"displayName\":\"PayPal\",\"countries\":[\"US\"],\"currencies\":[\"USD\"],\"hostedCheckout\":true}] }"), null)),
                java.util.List.of()));
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "", "none");

        var discovery = adapter.discoverMethods(new PaymentProvider.PaymentDiscoveryRequest("CH", "CHF", new BigDecimal("12.50")));

        assertThat(discovery.methods()).singleElement().satisfies(method -> {
            assertThat(method.selectionId()).isEqualTo(first + ":card");
            assertThat(method.providerInstanceId()).isEqualTo(first);
            assertThat(method.providerPluginId()).isEqualTo("payments.stripe");
            assertThat(method.releaseId()).isEqualTo(4L);
            assertThat(method.configurationGeneration()).isEqualTo(2);
        });
        assertThat(discovery.failures()).isEmpty();
    }

    @Test
    void newCheckoutUsesTheExplicitProviderInstanceAndCarriesTheSelectedMethod() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("country", "CH", "currency", "CHF");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 42L, 3, "a".repeat(64), scope);
        when(routed.invokeSelected(eq("payment.provider.v1"), eq("createCheckout"), eq(scope), eq(instanceId),
                eq("artio-order-order-123"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"providerReference\":\"session-selected\",\"checkoutUrl\":\"https://pay.test/session\",\"state\":\"PENDING\"}"), owner));
        var adapter = new ExtensionPaymentProvider(invoker, new ObjectMapper(), routed, "", "none");
        var request = new CheckoutRequest("order-123", "artio-order-order-123", new BigDecimal("12.50"), "CHF",
                "https://shop.test/success", "https://shop.test/cancel", Map.of("country", "CH"), "card");

        var result = adapter.createCheckoutOwned(request, instanceId);

        assertThat(result.result().providerReference()).isEqualTo("session-selected");
        assertThat(result.owner().instanceId()).isEqualTo(instanceId);
        org.mockito.ArgumentCaptor<Object> payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(routed).invokeSelected(eq("payment.provider.v1"), eq("createCheckout"), eq(scope), eq(instanceId),
                eq("artio-order-order-123"), payload.capture());
        assertThat(((CheckoutRequest) payload.getValue()).paymentMethodId()).isEqualTo("card");
    }
}
