package io.artio.extensions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Side-effect-free, deterministic resolver. The caller must persist the returned plan before invocation. */
public final class CapabilityRouter {
    private static final Set<String> SCOPE_KEYS = Set.of("storeId", "country", "currency", "channel",
            "originCountry", "destinationCountry", "organizationId", "contractId", "productClass", "purpose");

    public RoutePlan resolve(RouteRequest request, List<Binding> bindings) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(bindings, "bindings");
        var capability = CapabilityRegistry.find(request.capabilityId())
                .orElseThrow(() -> new RoutingException("Unsupported capability"));
        var operation = capability.operation(request.operationName())
                .orElseThrow(() -> new RoutingException("Unsupported capability operation"));
        validateScope(request.scope());

        if (operation.policy() == CapabilityRegistry.Policy.PINNED_ONE) {
            if (request.pinnedInstanceId() == null) throw new RoutingException("Pinned operation requires its historical instance owner");
            return bindings.stream().filter(b -> b.instanceId().equals(request.pinnedInstanceId())
                    && b.capabilityId().equals(request.capabilityId()) && b.operationName().equals(request.operationName())
                    && b.permissions().contains(operation.requiredPermission()) && matches(b.scope(), request.scope()))
                    .findFirst().map(b -> plan(request, operation, List.of(b)))
                    .orElseThrow(() -> new RoutingException("Historical operation owner is unavailable; fallback is forbidden"));
        }
        if (request.pinnedInstanceId() != null) throw new RoutingException("Only a pinned operation may supply a historical owner");
        List<Binding> eligible = bindings.stream()
                .filter(b -> b.capabilityId().equals(request.capabilityId()) && b.operationName().equals(request.operationName()))
                .filter(b -> b.state() == BindingState.ACTIVE && b.healthy())
                .filter(b -> !request.newCommitment() || b.instanceState() != InstanceState.DRAINING)
                .filter(b -> b.permissions().contains(operation.requiredPermission()))
                .filter(b -> matches(b.scope(), request.scope()))
                .toList();
        if (eligible.isEmpty()) throw new RoutingException("No eligible plugin instance is bound to this operation");

        int mostSpecific = eligible.stream().mapToInt(b -> b.scope().size()).max().orElse(0);
        List<Binding> scoped = eligible.stream().filter(b -> b.scope().size() == mostSpecific).toList();
        int highestPriority = scoped.stream().mapToInt(Binding::priority).max().orElse(0);
        List<Binding> prioritized = scoped.stream().filter(b -> b.priority() == highestPriority)
                .sorted(Comparator.comparing(b -> b.instanceId().toString())).toList();
        return switch (operation.policy()) {
            case AGGREGATE_READ -> plan(request, operation, eligible.stream()
                    .sorted(Comparator.comparing(b -> b.instanceId().toString())).toList());
            case EXCLUSIVE -> {
                if (prioritized.size() != 1) throw new AmbiguousRouteException("Exclusive capability has equally specific eligible bindings");
                yield plan(request, operation, prioritized);
            }
            case SELECT_ONE -> plan(request, operation, prioritized.subList(0, 1));
            case PRIMARY_REPLICAS -> plan(request, operation, prioritized.stream()
                    .collect(java.util.stream.Collectors.toMap(Binding::instanceId, binding -> binding,
                            (first, ignored) -> first, java.util.LinkedHashMap::new)).values().stream().toList());
            case PINNED_ONE -> throw new IllegalStateException("Pinned operation handled before candidate selection");
        };
    }

    private static RoutePlan plan(RouteRequest request, CapabilityRegistry.Operation operation, List<Binding> selected) {
        return new RoutePlan(request.capabilityId(), request.operationName(), operation.policy(), operation.sideEffecting(),
                request.scope(), selected.stream().map(Binding::instanceId).toList(), selected.stream().map(Binding::bindingId).toList());
    }

    private static void validateScope(Map<String, String> scope) {
        if (scope.size() > SCOPE_KEYS.size()) throw new RoutingException("Scope has too many dimensions");
        scope.forEach((key, value) -> {
            if (!SCOPE_KEYS.contains(key)) throw new RoutingException("Unsupported scope dimension: " + key);
            if (value == null || value.isBlank() || value.length() > 128) throw new RoutingException("Scope values must contain 1 to 128 characters");
        });
    }

    public static void validateBinding(String capabilityId, String operationName, Map<String, String> scope) {
        var capability = CapabilityRegistry.find(capabilityId)
                .orElseThrow(() -> new RoutingException("Unsupported capability"));
        capability.operation(operationName)
                .orElseThrow(() -> new RoutingException("Unsupported capability operation"));
        validateScope(scope == null ? Map.of() : scope);
    }

    /** Equal-precedence EXCLUSIVE rules that can match the same request would make the owner ambiguous. */
    public static boolean conflictsAtEqualExclusivePrecedence(Map<String, String> candidateScope, int candidatePriority,
            Map<String, String> existingScope, int existingPriority) {
        Map<String, String> candidate = candidateScope == null ? Map.of() : candidateScope;
        Map<String, String> existing = existingScope == null ? Map.of() : existingScope;
        return candidatePriority == existingPriority && candidate.size() == existing.size()
                && candidate.entrySet().stream().allMatch(entry -> !existing.containsKey(entry.getKey())
                        || existing.get(entry.getKey()).equals(entry.getValue()));
    }

    private static boolean matches(Map<String, String> bindingScope, Map<String, String> requestScope) {
        return bindingScope.entrySet().stream().allMatch(entry -> entry.getValue().equals(requestScope.get(entry.getKey())));
    }

    public record RouteRequest(String capabilityId, String operationName, Map<String, String> scope,
                               UUID pinnedInstanceId, boolean newCommitment) {
        public RouteRequest {
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(operationName, "operationName");
            scope = scope == null ? Map.of() : Map.copyOf(scope);
        }
    }

    public record Binding(UUID bindingId, UUID instanceId, String capabilityId, String operationName,
                          Map<String, String> scope, int priority, Set<String> permissions,
                          BindingState state, InstanceState instanceState, boolean healthy) {
        public Binding {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(instanceId, "instanceId");
            scope = scope == null ? Map.of() : Map.copyOf(scope);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(instanceState, "instanceState");
        }
    }

    public record RoutePlan(String capabilityId, String operationName, CapabilityRegistry.Policy policy,
                            boolean sideEffecting, Map<String, String> scope, List<UUID> instanceIds, List<UUID> bindingIds) {
        public RoutePlan { scope = Map.copyOf(scope); instanceIds = List.copyOf(instanceIds); bindingIds = List.copyOf(bindingIds); }
    }

    public enum BindingState { ACTIVE, DRAINING, DISABLED }
    public enum InstanceState { ENABLED, DRAINING, DISABLED, UNHEALTHY, FAILED }
    public static class RoutingException extends IllegalStateException { public RoutingException(String message) { super(message); } }
    public static final class AmbiguousRouteException extends RoutingException { public AmbiguousRouteException(String message) { super(message); } }
}
