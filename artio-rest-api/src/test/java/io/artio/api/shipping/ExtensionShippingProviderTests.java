package io.artio.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.commerce.ShippingProvider.Address;
import io.artio.extensions.commerce.ShippingProvider.ShipmentRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtensionShippingProviderTests {
    private final ManagedExtensionInvoker invoker = mock(ManagedExtensionInvoker.class);
    private final ExtensionShippingProvider provider = new ExtensionShippingProvider(invoker, new ObjectMapper(), "shipping.swiss-post");

    @Test
    void createsShipmentUsingStableCallerIdempotencyKey() throws Exception {
        var request = new ShipmentRequest("order-123", "priority", new Address("CH", "8000", "Zürich", "Main 1", null, "Ada"),
                new BigDecimal("1.25"), new BigDecimal("80.00"), "CHF", "ship-order-123");
        String stableKey = UUID.nameUUIDFromBytes("artio:createShipment:ship-order-123".getBytes(StandardCharsets.UTF_8)).toString();
        when(invoker.invoke(eq("shipping.swiss-post"), eq("shipping.provider.v1"), eq("createShipment"), eq(stableKey), eq(request)))
                .thenReturn(new ObjectMapper().readTree("{\"shipmentReference\":\"shipment-1\",\"trackingReference\":\"track-1\",\"labelUrl\":\"https://labels.test/1\"}"));

        var result = provider.createShipment(request);

        assertThat(result.trackingReference()).isEqualTo("track-1");
        assertThat(result.labelUrl()).isEqualTo("https://labels.test/1");
        verify(invoker).invoke("shipping.swiss-post", "shipping.provider.v1", "createShipment", stableKey, request);
    }

    @Test
    void rejectsMissingShipmentIdempotencyKeyBeforeCallingPlugin() {
        var request = new ShipmentRequest("order-123", "priority", new Address("CH", "8000", "Zürich", "Main 1", null, "Ada"),
                BigDecimal.ONE, BigDecimal.TEN, "CHF", " ");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.createShipment(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotency");
    }
}
