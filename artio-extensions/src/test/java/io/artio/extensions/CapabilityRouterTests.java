package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityRouterTests {
    private final CapabilityRouter router = new CapabilityRouter();

    @Test
    void choosesMostSpecificEligibleBindingAndExcludesDrainingForNewCommitment() {
        var general = binding("payment.process", Map.of(), 100, CapabilityRouter.InstanceState.ENABLED, true);
        var specific = binding("payment.process", Map.of("country", "CH"), 1, CapabilityRouter.InstanceState.ENABLED, true);
        var draining = binding("payment.process", Map.of("country", "CH"), 100, CapabilityRouter.InstanceState.DRAINING, true);
        var plan = router.resolve(request("createCheckout", Map.of("country", "CH"), null, true),
                List.of(general, specific, draining));
        assertThat(plan.instanceIds()).containsExactly(specific.instanceId());
    }

    @Test
    void aggregatesOnlyReadMethodsAndRejectsEqualExclusiveBindings() {
        var one = binding("payments.methods.read", Map.of(), 0, CapabilityRouter.InstanceState.ENABLED, true,
                "payment.provider.v1", "listMethods");
        var two = binding("payments.methods.read", Map.of(), 0, CapabilityRouter.InstanceState.ENABLED, true,
                "payment.provider.v1", "listMethods");
        assertThat(router.resolve(request("listMethods", Map.of(), null, false), List.of(one, two)).instanceIds())
                .containsExactlyElementsOf(List.of(one, two).stream().map(CapabilityRouter.Binding::instanceId)
                        .sorted(java.util.Comparator.comparing(UUID::toString)).toList());

        var exclusiveOne = binding("catalog.read", Map.of(), 0, CapabilityRouter.InstanceState.ENABLED, true,
                "catalog.enrichment.v1", "enrich");
        var exclusiveTwo = binding("catalog.read", Map.of(), 0, CapabilityRouter.InstanceState.ENABLED, true,
                "catalog.enrichment.v1", "enrich");
        assertThatThrownBy(() -> router.resolve(new CapabilityRouter.RouteRequest("catalog.enrichment.v1", "enrich", Map.of(), null, true),
                List.of(exclusiveOne, exclusiveTwo))).isInstanceOf(CapabilityRouter.RoutingException.class);
    }

    @Test
    void pinnedHistoricalOperationNeverFallsBackEvenWhenInstanceIsUnhealthy() {
        var historic = binding("payment.process", Map.of(), 0, CapabilityRouter.InstanceState.UNHEALTHY, false,
                "payment.provider.v1", "refund");
        var replacement = binding("payment.process", Map.of(), 100, CapabilityRouter.InstanceState.ENABLED, true,
                "payment.provider.v1", "refund");
        var request = request("refund", Map.of(), historic.instanceId(), false);
        assertThat(router.resolve(request, List.of(historic, replacement)).instanceIds()).containsExactly(historic.instanceId());
        assertThatThrownBy(() -> router.resolve(request("refund", Map.of(), UUID.randomUUID(), false), List.of(historic, replacement)))
                .isInstanceOf(CapabilityRouter.RoutingException.class).hasMessageContaining("fallback is forbidden");
    }

    @Test
    void rejectsUnknownScopeAndMissingAuthorization() {
        var candidate = binding("payment.process", Map.of(), 0, CapabilityRouter.InstanceState.ENABLED, true);
        assertThatThrownBy(() -> router.resolve(request("createCheckout", Map.of("email", "x"), null, true), List.of(candidate)))
                .isInstanceOf(CapabilityRouter.RoutingException.class).hasMessageContaining("Unsupported scope");
        assertThatThrownBy(() -> router.resolve(new CapabilityRouter.RouteRequest("payment.provider.v1", "refund", Map.of(), null, false), List.of(candidate)))
                .isInstanceOf(CapabilityRouter.RoutingException.class).hasMessageContaining("historical instance owner");
    }

    @Test
    void rejectsEqualPrecedenceExclusiveRulesWhoseScopesCanBothMatch() {
        assertThat(CapabilityRouter.conflictsAtEqualExclusivePrecedence(Map.of("country", "CH"), 5,
                Map.of("currency", "CHF"), 5)).isTrue();
        assertThat(CapabilityRouter.conflictsAtEqualExclusivePrecedence(Map.of("country", "CH"), 5,
                Map.of("country", "DE"), 5)).isFalse();
        assertThat(CapabilityRouter.conflictsAtEqualExclusivePrecedence(Map.of("country", "CH"), 5,
                Map.of("currency", "CHF"), 6)).isFalse();
        assertThat(CapabilityRouter.conflictsAtEqualExclusivePrecedence(Map.of(), 5,
                Map.of("country", "CH"), 5)).isFalse();
    }

    @Test
    void objectPlacementUsesDistinctInstancesInDeterministicPrimaryReplicaOrder() {
        UUID duplicateInstance = UUID.randomUUID();
        var firstBinding = storageBinding(duplicateInstance);
        var duplicateBinding = storageBinding(duplicateInstance);
        var replica = storageBinding(UUID.randomUUID());

        var plan = router.resolve(new CapabilityRouter.RouteRequest("storage.object.v1", "put", Map.of(), null, true),
                List.of(replica, duplicateBinding, firstBinding));

        assertThat(plan.instanceIds()).hasSize(2).doesNotHaveDuplicates();
        assertThat(plan.instanceIds()).isSortedAccordingTo(java.util.Comparator.comparing(UUID::toString));
        assertThat(plan.policy()).isEqualTo(CapabilityRegistry.Policy.PRIMARY_REPLICAS);
    }

    private static CapabilityRouter.Binding storageBinding(UUID instanceId) {
        return new CapabilityRouter.Binding(UUID.randomUUID(), instanceId, "storage.object.v1", "put", Map.of(), 0,
                Set.of("storage.object.write"), CapabilityRouter.BindingState.ACTIVE,
                CapabilityRouter.InstanceState.ENABLED, true);
    }

    private static CapabilityRouter.RouteRequest request(String operation, Map<String, String> scope, UUID pinned, boolean newCommitment) {
        return new CapabilityRouter.RouteRequest("payment.provider.v1", operation, scope, pinned, newCommitment);
    }

    private static CapabilityRouter.Binding binding(String permission, Map<String, String> scope, int priority,
            CapabilityRouter.InstanceState state, boolean healthy) {
        return binding(permission, scope, priority, state, healthy, "payment.provider.v1", "createCheckout");
    }

    private static CapabilityRouter.Binding binding(String permission, Map<String, String> scope, int priority,
            CapabilityRouter.InstanceState state, boolean healthy, String capability, String operation) {
        return new CapabilityRouter.Binding(UUID.randomUUID(), UUID.randomUUID(), capability, operation,
                scope, priority, Set.of(permission), CapabilityRouter.BindingState.ACTIVE, state, healthy);
    }
}
