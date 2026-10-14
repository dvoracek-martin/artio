package io.artio.extensions.commerce;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Provider-neutral shipping port implemented by carrier Docker extensions. */
public interface ShippingProvider {
    ShippingCapabilities capabilities();
    List<ShippingMethod> methods(Address destination, String currency);
    ShippingQuote quote(ShippingRequest request);
    ShipmentResult createShipment(ShipmentRequest request);
    TrackingResult track(String trackingReference);

    record ShippingCapabilities(boolean quotes, boolean shipmentCreation, boolean labels, boolean tracking,
                                List<String> countries) {
        public ShippingCapabilities { countries = countries == null ? List.of() : List.copyOf(countries); }
    }
    record Address(String countryCode, String postalCode, String locality, String addressLine1,
                   String addressLine2, String recipientName) { }
    record ShippingMethod(String methodId, String name, String estimatedDelivery, Map<String, String> metadata) {
        public ShippingMethod { metadata = metadata == null ? Map.of() : Map.copyOf(metadata); }
    }
    record ShippingRequest(String orderId, String methodId, Address destination, BigDecimal parcelWeightKg,
                           BigDecimal declaredValue, String currency) { }
    record ShippingQuote(String methodId, BigDecimal amount, String currency, String estimatedDelivery) { }
    record ShipmentRequest(String orderId, String methodId, Address destination, BigDecimal parcelWeightKg,
                           BigDecimal declaredValue, String currency, String idempotencyKey) { }
    record ShipmentResult(String shipmentReference, String trackingReference, String labelUrl) { }
    record TrackingResult(String trackingReference, String status, String lastLocation, String occurredAt) { }
}
