package io.artio.api.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.ShippingProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Maps the shipping semantic port to an enabled shipping.provider.v1 Docker plugin. */
@Component
public class ExtensionShippingProvider implements ShippingProvider {
    private static final String EXTENSION_POINT = "shipping.provider.v1";
    private final ManagedExtensionInvoker extensions;
    private final ObjectMapper mapper;
    private final String pluginId;
    private final CommerceCapabilityInvoker capabilityInvoker;
    private final boolean legacyEnabled;

    @Autowired
    public ExtensionShippingProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper,
            CommerceCapabilityInvoker capabilityInvoker,
            @Value("${artio.shipping.extension-plugin-id:}") String pluginId,
            @Value("${artio.shipping.provider:static}") String providerMode) {
        if (pluginId != null && !pluginId.isBlank() && !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_SHIPPING_EXTENSION_PLUGIN_ID must identify a shipping plugin");
        }
        this.extensions = extensions;
        this.mapper = mapper;
        this.pluginId = pluginId == null ? "" : pluginId;
        this.capabilityInvoker = capabilityInvoker;
        this.legacyEnabled = "extension".equals(providerMode);
    }

    ExtensionShippingProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper, String pluginId) {
        this(extensions, mapper, null, pluginId, "extension");
    }

    @Override public ShippingCapabilities capabilities() {
        if (capabilityInvoker != null && (capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "createShipment")
                || capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "track")
                || capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "quote")
                || capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "listMethods"))) {
            return new ShippingCapabilities(false, capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "createShipment"),
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "createShipment"),
                    capabilityInvoker.hasEnabledBinding(EXTENSION_POINT, "track"), List.of());
        }
        if (!legacyEnabled || pluginId.isBlank()) return new ShippingCapabilities(false, false, false, false, List.of());
        JsonNode response = invoke("capabilities", Map.of(), "capabilities");
        try {
            for (String field : List.of("quotes", "shipmentCreation", "labels", "tracking")) {
                if (response == null || !response.path(field).isBoolean()) throw new IllegalArgumentException();
            }
            ShippingCapabilities capabilities = read(response, ShippingCapabilities.class);
            if (capabilities.countries().size() > 250 || capabilities.countries().stream().anyMatch(country -> country == null || !country.matches("[A-Z]{2}"))) {
                throw new IllegalArgumentException();
            }
            return capabilities;
        }
        catch (RuntimeException invalid) { throw protocol("Shipping plugin returned invalid capabilities"); }
    }

    @Override public List<ShippingMethod> methods(Address destination, String currency) {
        JsonNode response = invoke("methods", Map.of("destination", destination, "currency", currency),
                "methods:" + destination.countryCode() + ":" + currency);
        JsonNode entries = response == null ? null : response.get("methods");
        if (entries == null || !entries.isArray() || entries.size() > 100) throw protocol("Shipping plugin must return at most 100 methods");
        List<ShippingMethod> result = new ArrayList<>();
        try { for (JsonNode entry : entries) result.add(mapper.treeToValue(entry, ShippingMethod.class)); }
        catch (Exception invalid) { throw protocol("Shipping plugin returned an invalid shipping method"); }
        if (result.stream().anyMatch(method -> method.methodId() == null || method.methodId().isBlank())
                || result.stream().map(ShippingMethod::methodId).distinct().count() != result.size()) {
            throw protocol("Shipping plugin returned a method without an identifier");
        }
        return List.copyOf(result);
    }

    @Override public ShippingQuote quote(ShippingRequest request) {
        ShippingQuote quote = read(invoke("quote", request, "quote:" + request.orderId() + ":" + request.methodId()), ShippingQuote.class);
        if (quote.methodId() == null || !quote.methodId().equals(request.methodId()) || quote.amount() == null
                || quote.amount().signum() < 0 || quote.currency() == null || !quote.currency().matches("[A-Z]{3}")) {
            throw protocol("Shipping plugin returned an invalid quote");
        }
        return quote;
    }

    @Override public ShipmentResult createShipment(ShipmentRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) throw new IllegalArgumentException("Shipment idempotency key is required");
        ShipmentResult result = read(invoke("createShipment", request, request.idempotencyKey()), ShipmentResult.class);
        if (result.shipmentReference() == null || result.shipmentReference().isBlank() || result.shipmentReference().length() > 255
                || result.trackingReference() != null && result.trackingReference().length() > 255
                || result.labelUrl() != null && result.labelUrl().length() > 2_100_000) throw protocol("Shipping plugin returned an invalid shipment reference, tracking reference or label");
        return result;
    }

    @Override public OwnedResult<ShipmentResult> createShipmentOwned(ShipmentRequest request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank())
            throw new IllegalArgumentException("Shipment idempotency key is required");
        Map<String, String> scope = Map.of("destinationCountry", request.destination().countryCode(), "currency", request.currency());
        var invocation = v2("createShipment", scope, null, true, request.idempotencyKey(), request);
        if (invocation == null) return ShippingProvider.super.createShipmentOwned(request);
        ShipmentResult result = validateShipment(read(invocation.response(), ShipmentResult.class));
        return new OwnedResult<>(result, owner(invocation.owner()));
    }

    @Override public TrackingResult track(String trackingReference) {
        TrackingResult result = read(invoke("track", Map.of("trackingReference", trackingReference), "track:" + trackingReference), TrackingResult.class);
        if (result.trackingReference() == null || !result.trackingReference().equals(trackingReference)
                || result.status() == null || result.status().isBlank() || result.status().length() > 80
                || result.lastLocation() != null && result.lastLocation().length() > 200
                || result.occurredAt() != null && result.occurredAt().length() > 64)
            throw protocol("Shipping plugin returned invalid tracking data");
        return result;
    }

    @Override public OwnedResult<TrackingResult> track(ProviderOwner owner, String trackingReference) {
        if (owner == null || owner.instanceId() == null) return ShippingProvider.super.track(owner, trackingReference);
        var invocation = v2("track", owner.scope(), owner.instanceId(), false,
                "track:" + trackingReference, Map.of("trackingReference", trackingReference));
        return new OwnedResult<>(validateTracking(read(invocation.response(), TrackingResult.class), trackingReference), owner(invocation.owner()));
    }

    private ShipmentResult validateShipment(ShipmentResult result) {
        if (result.shipmentReference() == null || result.shipmentReference().isBlank() || result.shipmentReference().length() > 255
                || result.trackingReference() != null && result.trackingReference().length() > 255
                || result.labelUrl() != null && result.labelUrl().length() > 2_100_000)
            throw protocol("Shipping plugin returned an invalid shipment reference, tracking reference or label");
        return result;
    }

    private TrackingResult validateTracking(TrackingResult result, String trackingReference) {
        if (result.trackingReference() == null || !result.trackingReference().equals(trackingReference)
                || result.status() == null || result.status().isBlank() || result.status().length() > 80
                || result.lastLocation() != null && result.lastLocation().length() > 200
                || result.occurredAt() != null && result.occurredAt().length() > 64)
            throw protocol("Shipping plugin returned invalid tracking data");
        return result;
    }

    private CommerceCapabilityInvoker.Invocation v2(String operation, Map<String, String> scope, UUID pinnedInstance,
            boolean newCommitment, String idempotencyKey, Object payload) {
        if (capabilityInvoker == null) return null;
        String key = UUID.nameUUIDFromBytes(("artio:" + operation + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
        return capabilityInvoker.invoke("shipping.provider.v1", operation, scope, pinnedInstance, newCommitment, key, payload);
    }

    private static ProviderOwner owner(CommerceCapabilityInvoker.Owner owner) {
        return new ProviderOwner(owner.instanceId(), owner.releaseId(), owner.configurationGeneration(),
                owner.externalAccountFingerprint(), owner.scope());
    }

    private JsonNode invoke(String operation, Object payload, String sourceKey) {
        if (!legacyEnabled || pluginId.isBlank()) throw new ExtensionRuntimePort.ExtensionUnavailableException("No shipping provider is configured");
        String runtimeKey = UUID.nameUUIDFromBytes(("artio:" + operation + ":" + sourceKey).getBytes(StandardCharsets.UTF_8)).toString();
        return extensions.invoke(pluginId, EXTENSION_POINT, operation, runtimeKey, payload);
    }

    private <T> T read(JsonNode response, Class<T> type) {
        try {
            if (response == null || !response.isObject()) throw new IllegalArgumentException();
            return mapper.treeToValue(response, type);
        } catch (Exception invalid) { throw protocol("Shipping plugin returned an invalid " + type.getSimpleName()); }
    }

    private static ExtensionRuntimePort.ExtensionProtocolException protocol(String message) {
        return new ExtensionRuntimePort.ExtensionProtocolException(message);
    }
}
