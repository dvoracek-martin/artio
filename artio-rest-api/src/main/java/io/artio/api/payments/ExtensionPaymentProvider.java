package io.artio.api.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapts the versioned payment provider contract to a managed payment Docker extension. */
@Component
public class ExtensionPaymentProvider implements PaymentProvider {
    private static final String EXTENSION_POINT = "payment.provider.v1";
    private final ManagedExtensionInvoker extensions;
    private final ObjectMapper mapper;
    private final String pluginId;
    private final CommerceCapabilityInvoker capabilityInvoker;
    private final boolean legacyEnabled;

    @Autowired
    public ExtensionPaymentProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper,
            CommerceCapabilityInvoker capabilityInvoker,
            @Value("${artio.payments.extension-plugin-id:}") String pluginId,
            @Value("${artio.payments.provider:none}") String providerMode) {
        if (pluginId != null && !pluginId.isBlank() && !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_PAYMENTS_EXTENSION_PLUGIN_ID must identify a payment plugin");
        }
        this.extensions = extensions;
        this.mapper = mapper;
        this.pluginId = pluginId == null ? "" : pluginId;
        this.capabilityInvoker = capabilityInvoker;
        this.legacyEnabled = "extension".equals(providerMode);
    }

    /** Compatibility constructor retained for focused adapter tests and v1 plugin deployments. */
    ExtensionPaymentProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper, String pluginId) {
        this(extensions, mapper, null, pluginId, "extension");
    }

    @Override public PaymentCapabilities capabilities() {
        if (capabilityInvoker != null && capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "createCheckout")) {
            return new PaymentCapabilities(true, false,
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "capture"),
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "cancel"),
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "refund"),
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "verifyWebhook"), Optional.empty());
        }
        if (!legacyEnabled || pluginId.isBlank()) return new PaymentCapabilities(false, false, false, false, false, false, Optional.empty());
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

    @Override public PaymentMethodDiscovery discoverMethods(PaymentDiscoveryRequest request) {
        if (request == null || request.country() == null || !request.country().matches("[A-Z]{2}")
                || request.currency() == null || !request.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("Payment discovery requires an ISO country and currency");
        Map<String, String> scope = Map.of("country", request.country(), "currency", request.currency());
        Map<String, Object> payload = Map.of("country", request.country(), "currency", request.currency(),
                "amount", request.amount() == null ? java.math.BigDecimal.ZERO : request.amount());
        List<AvailablePaymentMethod> methods = new ArrayList<>();
        List<ProviderFailure> failures = new ArrayList<>();
        CommerceCapabilityInvoker.AggregateInvocation aggregate = capabilityInvoker == null
                ? new CommerceCapabilityInvoker.AggregateInvocation(List.of(), List.of())
                : capabilityInvoker.aggregateRead(EXTENSION_POINT, "listMethods", scope, payload);
        for (var result : aggregate.results()) {
            try {
                for (PaymentMethod method : parseMethods(result.response())) {
                    if (supports(method, request)) methods.add(new AvailablePaymentMethod(
                            result.instanceId() + ":" + method.methodId(), method.methodId(), method.displayName(),
                            result.pluginId(), result.instanceId(), result.releaseId(), result.configurationGeneration(),
                            method.hostedCheckout()));
                }
            } catch (RuntimeException invalid) {
                failures.add(new ProviderFailure(result.instanceId().toString(), "INVALID_METHOD_CATALOG"));
            }
        }
        aggregate.failures().forEach(result -> failures.add(new ProviderFailure(result.instanceId().toString(), result.errorCode())));
        if (aggregate.results().isEmpty() && aggregate.failures().isEmpty() && legacyEnabled && !pluginId.isBlank()) {
            try {
                var response = invoke("listMethods", payload, "discovery-" + request.country() + "-" + request.currency());
                for (PaymentMethod method : parseMethods(response)) {
                    if (supports(method, request)) methods.add(new AvailablePaymentMethod(
                            pluginId + ":" + method.methodId(), method.methodId(), method.displayName(), pluginId,
                            null, null, 0, method.hostedCheckout()));
                }
            } catch (RuntimeException unavailable) {
                failures.add(new ProviderFailure(pluginId, "PROVIDER_UNAVAILABLE"));
            }
        }
        return new PaymentMethodDiscovery(methods.stream().distinct().toList(), failures);
    }

    @Override public CheckoutResult createCheckout(CheckoutRequest request) {
        try {
            return read(invoke("createCheckout", request, request.idempotencyKey()), CheckoutResult.class);
        } catch (ExtensionRuntimePort.ExtensionCallException failure) {
            // Only explicit provider validation/authentication failures guarantee that no session was created.
            if (switch (failure.statusCode()) { case 400, 401, 403, 404, 422 -> true; default -> false; })
                throw new CheckoutRejectedException("PROVIDER_REJECTED_" + failure.statusCode());
            throw failure;
        }
    }

    @Override public OwnedResult<CheckoutResult> createCheckoutOwned(CheckoutRequest request) {
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("currency", request.currency());
        String country = request.metadata().get("country");
        if (country != null && country.matches("[A-Z]{2}")) scope.put("country", country);
        CommerceCapabilityInvoker.Invocation invocation = v2("createCheckout", scope, null, true,
                request.idempotencyKey(), request);
        if (invocation == null) return PaymentProvider.super.createCheckoutOwned(request);
        return new OwnedResult<>(read(invocation.response(), CheckoutResult.class), owner(invocation.owner()));
    }

    @Override public OwnedResult<CheckoutResult> createCheckoutOwned(CheckoutRequest request, UUID selectedInstanceId) {
        if (selectedInstanceId == null) return createCheckoutOwned(request);
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("currency", request.currency());
        String country = request.metadata().get("country");
        if (country != null && country.matches("[A-Z]{2}")) scope.put("country", country);
        CommerceCapabilityInvoker.Invocation invocation = capabilityInvoker.invokeSelected(EXTENSION_POINT, "createCheckout",
                scope, selectedInstanceId, request.idempotencyKey(), request);
        return new OwnedResult<>(read(invocation.response(), CheckoutResult.class), owner(invocation.owner()));
    }

    @Override public PaymentResult capture(String providerReference, String idempotencyKey) {
        return read(invoke("capture", Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey), idempotencyKey), PaymentResult.class);
    }

    @Override public OwnedResult<PaymentResult> capture(ProviderOwner owner, String providerReference, String idempotencyKey) {
        if (owner == null || owner.instanceId() == null) return PaymentProvider.super.capture(owner, providerReference, idempotencyKey);
        var invocation = v2("capture", owner.scope(), owner.instanceId(), false, idempotencyKey,
                Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey));
        return new OwnedResult<>(read(invocation.response(), PaymentResult.class), owner(invocation.owner()));
    }

    @Override public PaymentResult cancel(String providerReference, String idempotencyKey) {
        return read(invoke("cancel", Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey), idempotencyKey), PaymentResult.class);
    }

    @Override public OwnedResult<PaymentResult> cancel(ProviderOwner owner, String providerReference, String idempotencyKey) {
        if (owner == null || owner.instanceId() == null) return PaymentProvider.super.cancel(owner, providerReference, idempotencyKey);
        var invocation = v2("cancel", owner.scope(), owner.instanceId(), false, idempotencyKey,
                Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey));
        return new OwnedResult<>(read(invocation.response(), PaymentResult.class), owner(invocation.owner()));
    }

    @Override public PaymentResult refund(RefundRequest request) {
        return read(invoke("refund", request, request.idempotencyKey()), PaymentResult.class);
    }

    @Override public OwnedResult<PaymentResult> refund(ProviderOwner owner, RefundRequest request) {
        if (owner == null || owner.instanceId() == null) return PaymentProvider.super.refund(owner, request);
        var invocation = v2("refund", owner.scope(), owner.instanceId(), false, request.idempotencyKey(), request);
        return new OwnedResult<>(read(invocation.response(), PaymentResult.class), owner(invocation.owner()));
    }

    @Override public WebhookResult verifyWebhook(String signature, byte[] rawBody) {
        return verifyWebhook(Map.of("provider-signature", signature == null ? "" : signature), rawBody);
    }

    @Override public WebhookResult verifyWebhook(Map<String, String> headers, byte[] rawBody) {
        return read(invoke("verifyWebhook", Map.of("headers", headers == null ? Map.of() : headers,
                "rawBodyBase64", Base64.getEncoder().encodeToString(rawBody)), UUID.randomUUID().toString()), WebhookResult.class);
    }

    @Override public OwnedResult<WebhookResult> verifyWebhook(ProviderOwner owner, Map<String, String> headers,
            byte[] rawBody, String idempotencyKey) {
        if (owner == null || owner.instanceId() == null) return PaymentProvider.super.verifyWebhook(owner, headers, rawBody, idempotencyKey);
        var invocation = v2("verifyWebhook", owner.scope(), owner.instanceId(), false, idempotencyKey,
                Map.of("headers", headers == null ? Map.of() : headers,
                        "rawBodyBase64", Base64.getEncoder().encodeToString(rawBody)));
        return new OwnedResult<>(read(invocation.response(), WebhookResult.class), owner(invocation.owner()));
    }

    @Override public Optional<PaymentResult> reconcile(String providerReference, String idempotencyKey) {
        JsonNode response = invoke("reconcile", Map.of("providerReference", providerReference,
                "idempotencyKey", idempotencyKey), idempotencyKey);
        if (!response.has("result") || response.get("result").isNull()) return Optional.empty();
        return Optional.of(read(response.get("result"), PaymentResult.class));
    }

    @Override public Optional<OwnedResult<PaymentResult>> reconcile(ProviderOwner owner, String providerReference, String idempotencyKey) {
        if (owner == null || owner.instanceId() == null) return PaymentProvider.super.reconcile(owner, providerReference, idempotencyKey);
        var invocation = v2("reconcile", owner.scope(), owner.instanceId(), false, idempotencyKey,
                Map.of("providerReference", providerReference, "idempotencyKey", idempotencyKey));
        JsonNode response = invocation.response();
        if (!response.has("result") || response.get("result").isNull()) return Optional.empty();
        return Optional.of(new OwnedResult<>(read(response.get("result"), PaymentResult.class), owner(invocation.owner())));
    }

    private CommerceCapabilityInvoker.Invocation v2(String operation, Map<String, String> scope, UUID pinnedInstance,
            boolean newCommitment, String idempotencyKey, Object payload) {
        if (capabilityInvoker == null) return null;
        return capabilityInvoker.invoke("payment.provider.v1", operation, scope, pinnedInstance, newCommitment,
                stableKey(operation, idempotencyKey), payload);
    }

    private static ProviderOwner owner(CommerceCapabilityInvoker.Owner owner) {
        return new ProviderOwner(owner.instanceId(), owner.releaseId(), owner.configurationGeneration(),
                owner.externalAccountFingerprint(), owner.scope());
    }

    private JsonNode invoke(String operation, Object payload, String idempotencyKey) {
        if (!legacyEnabled || pluginId.isBlank()) throw new ExtensionRuntimePort.ExtensionUnavailableException("No payment provider is configured");
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

    private List<PaymentMethod> parseMethods(JsonNode response) {
        JsonNode values = response == null ? null : response.isArray() ? response : response.get("methods");
        if (values == null || !values.isArray() || values.size() > 32)
            throw protocol("Payment plugin returned an invalid method catalog");
        List<PaymentMethod> methods = new ArrayList<>();
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        for (JsonNode value : values) {
            PaymentMethod method;
            try { method = mapper.treeToValue(value, PaymentMethod.class); }
            catch (Exception invalid) { throw protocol("Payment plugin returned an invalid method catalog"); }
            if (!identifiers.add(method.methodId())) throw protocol("Payment plugin returned duplicate method identifiers");
            methods.add(method);
        }
        return List.copyOf(methods);
    }

    private static boolean supports(PaymentMethod method, PaymentDiscoveryRequest request) {
        return method.hostedCheckout()
                && (method.countries().isEmpty() || method.countries().contains(request.country()))
                && (method.currencies().isEmpty() || method.currencies().contains(request.currency()));
    }

    private static ExtensionRuntimePort.ExtensionProtocolException protocol(String message) {
        return new ExtensionRuntimePort.ExtensionProtocolException(message);
    }
}
