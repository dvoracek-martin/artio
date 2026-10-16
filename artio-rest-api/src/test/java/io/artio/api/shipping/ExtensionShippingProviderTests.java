package io.artio.api.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.api.extensions.CommerceCapabilityInvoker;
import io.artio.extensions.commerce.ShippingProvider;
import io.artio.extensions.commerce.ShippingProvider.Address;
import io.artio.extensions.commerce.ShippingProvider.ShipmentRequest;
import io.artio.extensions.commerce.ShippingProvider.ShippingMethod;
import io.artio.extensions.commerce.ShippingProvider.ShippingRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.Map;
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

    @Test
    void mapsAndValidatesProviderQuote() {
        var request=new ShippingRequest("order-123","swiss-post-pri",new Address("CH","8000","Zürich","Main 1",null,"Ada"),
                new BigDecimal("1.250"),new BigDecimal("80.00"),"CHF");
        String stableKey=UUID.nameUUIDFromBytes("artio:quote:quote:order-123:swiss-post-pri".getBytes(StandardCharsets.UTF_8)).toString();
        when(invoker.invoke(eq("shipping.swiss-post"),eq("shipping.provider.v1"),eq("quote"),eq(stableKey),eq(request)))
                .thenReturn(new ObjectMapper().valueToTree(new io.artio.extensions.commerce.ShippingProvider.ShippingQuote("swiss-post-pri",new BigDecimal("9.50"),"CHF","Merchant configured")));

        var quote=provider.quote(request);

        assertThat(quote.amount()).isEqualByComparingTo("9.50");
        assertThat(quote.currency()).isEqualTo("CHF");
        verify(invoker).invoke("shipping.swiss-post","shipping.provider.v1","quote",stableKey,request);
    }

    @Test
    void tracksCarrierReferenceAndRejectsUnboundedProviderData() {
        when(invoker.invoke(eq("shipping.swiss-post"),eq("shipping.provider.v1"),eq("track"),eq(UUID.nameUUIDFromBytes(
                "artio:track:track:track-1".getBytes(StandardCharsets.UTF_8)).toString()),eq(java.util.Map.of("trackingReference","track-1"))))
                .thenReturn(new ObjectMapper().valueToTree(new io.artio.extensions.commerce.ShippingProvider.TrackingResult(
                        "track-1","IN_TRANSIT","Zürich","2026-10-14T10:00:00Z")));
        assertThat(provider.track("track-1").status()).isEqualTo("IN_TRANSIT");

        when(invoker.invoke(eq("shipping.swiss-post"),eq("shipping.provider.v1"),eq("track"),eq(UUID.nameUUIDFromBytes(
                "artio:track:track:track-2".getBytes(StandardCharsets.UTF_8)).toString()),eq(java.util.Map.of("trackingReference","track-2"))))
                .thenReturn(new ObjectMapper().valueToTree(new io.artio.extensions.commerce.ShippingProvider.TrackingResult(
                        "track-2","x".repeat(81),null,null)));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->provider.track("track-2"))
                .isInstanceOf(io.artio.extensions.ExtensionRuntimePort.ExtensionProtocolException.class);
    }

    @Test
    void createsShipmentWithDurableInstanceOwnership() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("destinationCountry", "CH", "currency", "CHF");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 81L, 2, "c".repeat(64), scope);
        var request = new ShipmentRequest("order-9", "priority", new Address("CH", "8000", "Zürich", "Main 1", null, "Ada"),
                BigDecimal.ONE, BigDecimal.TEN, "CHF", "ship-order-9");
        when(routed.invoke(eq("shipping.provider.v1"), eq("createShipment"), eq(scope), eq(null), eq(true),
                org.mockito.ArgumentMatchers.anyString(), eq(request))).thenReturn(new CommerceCapabilityInvoker.Invocation(
                new ObjectMapper().readTree("{\"shipmentReference\":\"shipment-9\",\"trackingReference\":\"track-9\"}"), owner));
        var adapter = new ExtensionShippingProvider(invoker, new ObjectMapper(), routed, "shipping.compat", "static");

        var result = adapter.createShipmentOwned(request);

        assertThat(result.result().trackingReference()).isEqualTo("track-9");
        assertThat(result.owner().instanceId()).isEqualTo(instanceId);
        assertThat(result.owner().releaseId()).isEqualTo(81L);
        assertThat(result.owner().scope()).isEqualTo(scope);
    }

    @Test
    void trackingUsesThePersistedCarrierInstanceAndScope() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("destinationCountry", "CH", "currency", "CHF");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 81L, 2, "d".repeat(64), scope);
        when(routed.invoke(eq("shipping.provider.v1"), eq("track"), eq(scope), eq(instanceId), eq(false),
                org.mockito.ArgumentMatchers.anyString(), eq(Map.of("trackingReference", "track-9"))))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"trackingReference\":\"track-9\",\"status\":\"IN_TRANSIT\"}"), owner));
        var adapter = new ExtensionShippingProvider(invoker, new ObjectMapper(), routed, "shipping.compat", "static");

        var result = adapter.track(new ShippingProvider.ProviderOwner(instanceId, 81L, 2, "d".repeat(64), scope), "track-9");

        assertThat(result.result().status()).isEqualTo("IN_TRANSIT");
        verify(routed).invoke(eq("shipping.provider.v1"), eq("track"), eq(scope), eq(instanceId), eq(false),
                org.mockito.ArgumentMatchers.anyString(), eq(Map.of("trackingReference", "track-9")));
    }

    @Test
    void v2ShipmentBindingsActivateWithoutLegacyEnvironmentSelection() {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        when(routed.hasEnabledBinding("shipping.provider.v1", "createShipment")).thenReturn(true);
        when(routed.hasEnabledBinding("shipping.provider.v1", "track")).thenReturn(true);
        var adapter = new ExtensionShippingProvider(invoker, new ObjectMapper(), routed, "", "static");

        var capabilities = adapter.capabilities();

        assertThat(capabilities.shipmentCreation()).isTrue();
        assertThat(capabilities.tracking()).isTrue();
        assertThat(capabilities.quotes()).isFalse();
    }

    @Test
    void aggregatesAndNamespacesV2CarrierMethodsAndQuotesThroughTheSelectedInstance() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var destination = new Address("CH", "8000", "Zürich", "Main 1", null, "Ada");
        var listScope = Map.of("destinationCountry", "CH", "currency", "CHF");
        when(routed.aggregateRead(eq("shipping.provider.v1"), eq("listMethods"), eq(listScope),
                org.mockito.ArgumentMatchers.any())).thenReturn(new CommerceCapabilityInvoker.AggregateInvocation(List.of(
                new CommerceCapabilityInvoker.AggregateProviderResult(instanceId, "shipping.swiss-post", 9, 3,
                        new ObjectMapper().readTree("{\"methods\":[{\"methodId\":\"priority\",\"name\":\"Priority\",\"estimatedDelivery\":\"Tomorrow\"}]}"), null)), List.of()));
        var adapter = new ExtensionShippingProvider(invoker, new ObjectMapper(), routed, "", "static");

        var methods = adapter.discoverMethods(destination, "CHF");
        assertThat(methods.methods()).extracting(ShippingMethod::methodId).containsExactly(instanceId + ":priority");
        assertThat(methods.partialFailure()).isFalse();

        var request = new ShippingRequest("cart:customer", instanceId + ":priority", destination,
                BigDecimal.ONE, BigDecimal.TEN, "CHF");
        var providerRequest = new ShippingRequest(request.orderId(), "priority", destination, BigDecimal.ONE, BigDecimal.TEN, "CHF");
        when(routed.aggregateReadForInstance(eq("shipping.provider.v1"), eq("quote"), eq(listScope), eq(instanceId), eq(providerRequest)))
                .thenReturn(new CommerceCapabilityInvoker.AggregateInvocation(List.of(
                        new CommerceCapabilityInvoker.AggregateProviderResult(instanceId, "shipping.swiss-post", 9, 3,
                                new ObjectMapper().readTree("{\"methodId\":\"priority\",\"amount\":\"8.50\",\"currency\":\"CHF\",\"estimatedDelivery\":\"Tomorrow\"}"), null)), List.of()));

        var quote = adapter.quote(request);

        assertThat(quote.methodId()).isEqualTo(instanceId + ":priority");
        assertThat(quote.amount()).isEqualByComparingTo("8.50");
        verify(routed).aggregateReadForInstance(eq("shipping.provider.v1"), eq("quote"), eq(listScope), eq(instanceId), eq(providerRequest));
    }

    @Test
    void shipmentCreationHonorsExplicitlySelectedEligibleInstance() throws Exception {
        CommerceCapabilityInvoker routed = mock(CommerceCapabilityInvoker.class);
        UUID instanceId = UUID.randomUUID();
        var scope = Map.of("destinationCountry", "CH", "currency", "CHF");
        var owner = new CommerceCapabilityInvoker.Owner(instanceId, 81L, 2, "c".repeat(64), scope);
        var request = new ShipmentRequest("order-selected", "priority", new Address("CH", "8000", "Zürich", "Main 1", null, "Ada"),
                BigDecimal.ONE, BigDecimal.TEN, "CHF", "ship-selected");
        when(routed.invokeSelected(eq("shipping.provider.v1"), eq("createShipment"), eq(scope), eq(instanceId),
                eq(UUID.nameUUIDFromBytes("artio:createShipment:ship-selected".getBytes(StandardCharsets.UTF_8)).toString()), eq(request)))
                .thenReturn(new CommerceCapabilityInvoker.Invocation(new ObjectMapper().readTree(
                        "{\"shipmentReference\":\"shipment-selected\",\"trackingReference\":\"track-selected\"}"), owner));
        var adapter = new ExtensionShippingProvider(invoker, new ObjectMapper(), routed, "", "static");

        var created = adapter.createShipmentOwned(request, instanceId);

        assertThat(created.owner().instanceId()).isEqualTo(instanceId);
        assertThat(created.result().shipmentReference()).isEqualTo("shipment-selected");
        verify(routed).invokeSelected(eq("shipping.provider.v1"), eq("createShipment"), eq(scope), eq(instanceId),
                org.mockito.ArgumentMatchers.anyString(), eq(request));
    }
}
