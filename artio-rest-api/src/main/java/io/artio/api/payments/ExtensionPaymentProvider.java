package io.artio.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adapts the versioned payment provider contract to a managed payment Docker extension. */
@Component
@ConditionalOnProperty(name = "artio.payments.provider", havingValue = "extension")
public class ExtensionPaymentProvider implements PaymentProvider {
    private static final String EXTENSION_POINT = "payment.provider.v1";
    private final ManagedExtensionInvoker extensions;
    private final ObjectMapper mapper;
    private final String pluginId;

    public ExtensionPaymentProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper,
            @Value("${artio.payments.extension-plugin-id:}") String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_PAYMENTS_EXTENSION_PLUGIN_ID must identify a payment plugin");
        }
        this.extensions = extensions;
        this.mapper = mapper;
        this.pluginId = pluginId;
    }

    @Override public PaymentCapabilities capabilities() {
        JsonNode response = invoke("capabilities", Map.of(), "capabilities");
        try {
            JsonNode expiry = response.get("maximumCheckoutExpirySeconds");
            return new PaymentCapabilities(bool(response, "hostedCheckout"), bool(response, "authorization"),
                    bool(response, "capture"), bool(response, "cancellation"), bool(response, "refunds"),
                    bool(response, "webhooks"), expiry == null || expiry.isNull() ? Optional.empty()
                            : Optional.of(Duration.ofSeconds(expiry.longValue())));
        } catch (RuntimeException invalid) {
            throw protocol("Payment plugin returned invalid capabilities");
        }
    }

    @Override public CheckoutResult createCheckout(CheckoutRequest request) {
        return read(invoke("createCheckout", request, request.idempotencyKey()), CheckoutResult.class);
    }

    @Override public PaymentResult capture(String providerReference, String idempotencyKey) {
        return read(invoke("capture", Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey), idempotencyKey), PaymentResult.class);
    }

    @Override public PaymentResult cancel(String providerReference, String idempotencyKey) {
        return read(invoke("cancel", Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey), idempotencyKey), PaymentResult.class);
    }

    @Override public PaymentResult refund(RefundRequest request) {
        return read(invoke("refund", request, request.idempotencyKey()), PaymentResult.class);
    }

    @Override public WebhookResult verifyWebhook(String signature, byte[] rawBody) {
        return read(invoke("verifyWebhook", Map.of("signature", signature == null ? "" : signature,
                "rawBodyBase64", Base64.getEncoder().encodeToString(rawBody)), UUID.randomUUID().toString()), WebhookResult.class);
    }

    @Override public Optional<PaymentResult> reconcile(String providerReference, String idempotencyKey) {
        JsonNode response = invoke("reconcile", Map.of("providerReference", providerReference,
                "idempotencyKey", idempotencyKey), idempotencyKey);
        if (!response.has("result") || response.get("result").isNull()) return Optional.empty();
        return Optional.of(read(response.get("result"), PaymentResult.class));
    }

    private JsonNode invoke(String operation, Object payload, String idempotencyKey) {
        return extensions.invoke(pluginId, EXTENSION_POINT, operation, stableKey(operation, idempotencyKey), payload);
    }

    private static String stableKey(String operation, String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("Payment idempotency key is required");
        return UUID.nameUUIDFromBytes(("artio:" + operation + ":" + sourceKey).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private <T> T read(JsonNode response, Class<T> type) {
        try {
            if (response == null || !response.isObject()) throw new IllegalArgumentException();
            return mapper.treeToValue(response, type);
        } catch (Exception invalid) { throw protocol("Payment plugin returned an invalid " + type.getSimpleName()); }
    }

    private static boolean bool(JsonNode response, String field) {
        JsonNode value = response.get(field);
        if (value == null || !value.isBoolean()) throw new IllegalArgumentException("Missing boolean");
        return value.booleanValue();
    }

    private static ExtensionRuntimePort.ExtensionProtocolException protocol(String message) {
        return new ExtensionRuntimePort.ExtensionProtocolException(message);
    }
}
