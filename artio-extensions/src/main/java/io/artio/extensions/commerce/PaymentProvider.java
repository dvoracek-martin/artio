package io.artio.extensions.commerce;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provider-neutral payment port implemented by payment Docker extensions. */
public interface PaymentProvider {
    PaymentCapabilities capabilities();
    default PaymentMethodDiscovery discoverMethods(PaymentDiscoveryRequest request) {
        return new PaymentMethodDiscovery(List.of(), List.of());
    }
    CheckoutResult createCheckout(CheckoutRequest request);
    default OwnedResult<CheckoutResult> createCheckoutOwned(CheckoutRequest request) {
        return new OwnedResult<>(createCheckout(request), null);
    }
    default OwnedResult<CheckoutResult> createCheckoutOwned(CheckoutRequest request, UUID selectedInstanceId) {
        if (selectedInstanceId != null) throw new IllegalArgumentException("This payment provider cannot honor an instance selection");
        return createCheckoutOwned(request);
    }
    PaymentResult capture(String providerReference, String idempotencyKey);
    default OwnedResult<PaymentResult> capture(ProviderOwner owner, String providerReference, String idempotencyKey) {
        return new OwnedResult<>(capture(providerReference, idempotencyKey), owner);
    }
    PaymentResult cancel(String providerReference, String idempotencyKey);
    default OwnedResult<PaymentResult> cancel(ProviderOwner owner, String providerReference, String idempotencyKey) {
        return new OwnedResult<>(cancel(providerReference, idempotencyKey), owner);
    }
    PaymentResult refund(RefundRequest request);
    default OwnedResult<PaymentResult> refund(ProviderOwner owner, RefundRequest request) {
        return new OwnedResult<>(refund(request), owner);
    }
    WebhookResult verifyWebhook(String signature, byte[] rawBody);
    /** Provider-specific signed headers, restricted by Core to its allowlisted webhook header set. */
    default WebhookResult verifyWebhook(Map<String, String> headers, byte[] rawBody) {
        return verifyWebhook(headers == null ? null : headers.get("provider-signature"), rawBody);
    }
    default OwnedResult<WebhookResult> verifyWebhook(ProviderOwner owner, Map<String, String> headers,
            byte[] rawBody, String idempotencyKey) {
        return new OwnedResult<>(verifyWebhook(headers, rawBody), owner);
    }
    Optional<PaymentResult> reconcile(String providerReference, String idempotencyKey);
    default Optional<OwnedResult<PaymentResult>> reconcile(ProviderOwner owner, String providerReference, String idempotencyKey) {
        return reconcile(providerReference, idempotencyKey).map(result -> new OwnedResult<>(result, owner));
    }

    record ProviderOwner(UUID instanceId, Long releaseId, int configurationGeneration,
                         String externalAccountFingerprint, Map<String, String> scope) {
        public ProviderOwner { scope = scope == null ? Map.of() : Map.copyOf(scope); }
        public ProviderOwner(UUID instanceId, Long releaseId, int configurationGeneration, String externalAccountFingerprint) {
            this(instanceId, releaseId, configurationGeneration, externalAccountFingerprint, Map.of());
        }
    }
    record OwnedResult<T>(T result, ProviderOwner owner) { }

    record PaymentCapabilities(boolean hostedCheckout, boolean authorization, boolean capture,
                               boolean cancellation, boolean refunds, boolean webhooks,
                               Optional<Duration> maximumCheckoutExpiry) {
        public PaymentCapabilities {
            maximumCheckoutExpiry = maximumCheckoutExpiry == null ? Optional.empty() : maximumCheckoutExpiry;
        }
    }

    record PaymentDiscoveryRequest(String country, String currency, BigDecimal amount) { }
    record PaymentMethodDiscovery(List<AvailablePaymentMethod> methods, List<ProviderFailure> failures) {
        public PaymentMethodDiscovery { methods = List.copyOf(methods); failures = List.copyOf(failures); }
        public boolean partialFailure() { return !failures.isEmpty(); }
    }
    record AvailablePaymentMethod(String selectionId, String methodId, String displayName, String providerPluginId,
            UUID providerInstanceId, Long releaseId, int configurationGeneration, boolean hostedCheckout) { }
    record ProviderFailure(String providerId, String errorCode) { }
    record PaymentMethod(String methodId, String displayName, List<String> countries, List<String> currencies,
                         boolean hostedCheckout) {
        public PaymentMethod {
            if (methodId == null || !methodId.matches("[a-z][a-z0-9._-]{0,63}"))
                throw new IllegalArgumentException("Invalid payment method identifier");
            if (displayName == null || displayName.isBlank() || displayName.length() > 80)
                throw new IllegalArgumentException("Invalid payment method display name");
            countries = countries == null ? List.of() : List.copyOf(countries);
            currencies = currencies == null ? List.of() : currencies.stream().map(value -> value.toUpperCase(java.util.Locale.ROOT)).toList();
        }
    }
    record CheckoutRequest(String orderId, String idempotencyKey, BigDecimal amount, String currency,
                           String successUrl, String cancelUrl, Map<String, String> metadata, String paymentMethodId) {
        public CheckoutRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
        public CheckoutRequest(String orderId, String idempotencyKey, BigDecimal amount, String currency,
                String successUrl, String cancelUrl, Map<String, String> metadata) {
            this(orderId, idempotencyKey, amount, currency, successUrl, cancelUrl, metadata, null);
        }
    }

    record CheckoutResult(String providerReference, String checkoutUrl, PaymentState state) { }
    record RefundRequest(String providerReference, String idempotencyKey, BigDecimal amount, String currency) { }
    record PaymentResult(String providerReference, String transactionReference, PaymentState state,
                         BigDecimal amount, String currency, String failureCode) {
        public PaymentResult(String providerReference, PaymentState state, BigDecimal amount, String currency,
                String failureCode) {
            this(providerReference, null, state, amount, currency, failureCode);
        }
    }
    record WebhookResult(String providerEventId, String eventType, String providerReference, String transactionReference,
                         PaymentState state, BigDecimal amount, String currency, boolean verified) {
        public WebhookResult(String providerEventId, String eventType, String providerReference,
                PaymentState state, BigDecimal amount, String currency, boolean verified) {
            this(providerEventId, eventType, providerReference, null, state, amount, currency, verified);
        }
        /** Compatibility constructor for non-settlement events that do not carry an amount. */
        public WebhookResult(String providerEventId, String eventType, String providerReference,
                PaymentState state, boolean verified) {
            this(providerEventId, eventType, providerReference, null, state, null, null, verified);
        }
    }
    enum PaymentState { PENDING, AUTHORIZED, CAPTURED, DISPUTED, DISPUTE_WON, DISPUTE_LOST, REVERSED, CANCELLED, REFUNDED, FAILED, EXPIRED }

    /** A checkout rejection that the provider contract guarantees created no remote session or charge. */
    final class CheckoutRejectedException extends RuntimeException {
        private final String code;
        public CheckoutRejectedException(String code) {
            super("Payment provider definitively rejected checkout");
            if (code == null || !code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid provider rejection code");
            this.code = code;
        }
        public String code() { return code; }
    }
}
