package io.artio.extensions.commerce;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Provider-neutral payment port implemented by payment Docker extensions. */
public interface PaymentProvider {
    PaymentCapabilities capabilities();
    CheckoutResult createCheckout(CheckoutRequest request);
    PaymentResult capture(String providerReference, String idempotencyKey);
    PaymentResult cancel(String providerReference, String idempotencyKey);
    PaymentResult refund(RefundRequest request);
    WebhookResult verifyWebhook(String signature, byte[] rawBody);
    /** Provider-specific signed headers, restricted by Core to its allowlisted webhook header set. */
    default WebhookResult verifyWebhook(Map<String, String> headers, byte[] rawBody) {
        return verifyWebhook(headers == null ? null : headers.get("provider-signature"), rawBody);
    }
    Optional<PaymentResult> reconcile(String providerReference, String idempotencyKey);

    record PaymentCapabilities(boolean hostedCheckout, boolean authorization, boolean capture,
                               boolean cancellation, boolean refunds, boolean webhooks,
                               Optional<Duration> maximumCheckoutExpiry) {
        public PaymentCapabilities {
            maximumCheckoutExpiry = maximumCheckoutExpiry == null ? Optional.empty() : maximumCheckoutExpiry;
        }
    }

    record CheckoutRequest(String orderId, String idempotencyKey, BigDecimal amount, String currency,
                           String successUrl, String cancelUrl, Map<String, String> metadata) {
        public CheckoutRequest { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }

    record CheckoutResult(String providerReference, String checkoutUrl, PaymentState state) { }
    record RefundRequest(String providerReference, String idempotencyKey, BigDecimal amount, String currency) { }
    record PaymentResult(String providerReference, PaymentState state, BigDecimal amount, String currency,
                         String failureCode) { }
    record WebhookResult(String providerEventId, String eventType, String providerReference,
                         PaymentState state, BigDecimal amount, String currency, boolean verified) {
        /** Compatibility constructor for non-settlement events that do not carry an amount. */
        public WebhookResult(String providerEventId, String eventType, String providerReference,
                PaymentState state, boolean verified) {
            this(providerEventId, eventType, providerReference, state, null, null, verified);
        }
    }
    enum PaymentState { PENDING, AUTHORIZED, CAPTURED, CANCELLED, REFUNDED, FAILED, EXPIRED }
}
