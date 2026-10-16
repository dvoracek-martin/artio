package io.artio.extensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryTests {
    @Test
    void registryIsClosedAndFinancialSideEffectsRequirePinnedOwnerAfterCheckout() {
        assertTrue(CapabilityRegistry.find("payments.unknown.v1").isEmpty());
        var payment = CapabilityRegistry.find("payment.provider.v1").orElseThrow();
        assertEquals(CapabilityRegistry.Policy.SELECT_ONE, payment.operation("createCheckout").orElseThrow().policy());
        for (String operation : new String[] {"capture", "cancel", "refund", "reconcile", "verifyWebhook"}) {
            var rule = payment.operation(operation).orElseThrow();
            assertEquals(CapabilityRegistry.Policy.PINNED_ONE, rule.policy(), operation);
            assertTrue(rule.sideEffecting(), operation);
        }
        assertTrue(payment.operation("refund").orElseThrow().requiredPermission().equals("payment.process"));
    }

    @Test
    void shippingAndObjectOperationsDeclareCoreOwnedExecutionRules() {
        var shipping = CapabilityRegistry.find("shipping.provider.v1").orElseThrow();
        assertEquals(CapabilityRegistry.Policy.AGGREGATE_READ, shipping.operation("quote").orElseThrow().policy());
        assertEquals(CapabilityRegistry.Policy.SELECT_ONE, shipping.operation("createShipment").orElseThrow().policy());
        assertEquals(CapabilityRegistry.Policy.PINNED_ONE, shipping.operation("purchaseLabel").orElseThrow().policy());

        var storage = CapabilityRegistry.find("storage.object.v1").orElseThrow();
        assertEquals(CapabilityRegistry.Policy.PRIMARY_REPLICAS, storage.operation("put").orElseThrow().policy());
        assertTrue(storage.operation("put").orElseThrow().sideEffecting());
        assertFalse(storage.operation("get").orElseThrow().sideEffecting());
    }
}
