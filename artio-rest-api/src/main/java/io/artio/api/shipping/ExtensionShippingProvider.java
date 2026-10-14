package io.artio.api.shipping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.ShippingProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Maps the shipping semantic port to an enabled shipping.provider.v1 Docker plugin. */
@Component
@ConditionalOnProperty(name = "artio.shipping.provider", havingValue = "extension")
public class ExtensionShippingProvider implements ShippingProvider {
    private static final String EXTENSION_POINT = "shipping.provider.v1";
    private final ManagedExtensionInvoker extensions;
    private final ObjectMapper mapper;
    private final String pluginId;

    public ExtensionShippingProvider(ManagedExtensionInvoker extensions, ObjectMapper mapper,
            @Value("${artio.shipping.extension-plugin-id:}") String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_SHIPPING_EXTENSION_PLUGIN_ID must identify a shipping plugin");
        }
        this.extensions = extensions;
        this.mapper = mapper;
        this.pluginId = pluginId;
    }

    @Override public ShippingCapabilities capabilities() {
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

    @Override public TrackingResult track(String trackingReference) {
        TrackingResult result = read(invoke("track", Map.of("trackingReference", trackingReference), "track:" + trackingReference), TrackingResult.class);
        if (result.trackingReference() == null || !result.trackingReference().equals(trackingReference)
                || result.status() == null || result.status().isBlank()) throw protocol("Shipping plugin returned invalid tracking data");
        return result;
    }

    private JsonNode invoke(String operation, Object payload, String sourceKey) {
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
