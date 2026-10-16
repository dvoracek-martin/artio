package io.artio.api.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.CapabilityRegistry;
import io.artio.extensions.CapabilityRouter;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifestV2;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Core-owned durable invocation path for commerce side effects routed through v2 bindings. */
@Service
public class CommerceCapabilityInvoker {
    private static final TypeReference<Map<String, Object>> OBJECTS = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRINGS = new TypeReference<>() { };
    private static final Duration DEADLINE = Duration.ofSeconds(15);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EncryptionService encryption;
    private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;

    public CommerceCapabilityInvoker(JdbcTemplate jdbc, ObjectMapper mapper, EncryptionService encryption,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.encryption = encryption;
        this.runtimeProvider = runtimeProvider;
    }

    /** Returns null only when no v2 binding exists, allowing the configured v1 compatibility adapter. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Invocation invoke(String capabilityId, String operationName, Map<String, String> scope,
            UUID pinnedInstanceId, boolean newCommitment, String idempotencyKey, Object payload) {
        return invokeRouted(capabilityId, operationName, scope, pinnedInstanceId, null, newCommitment,
                idempotencyKey, payload);
    }

    /** Executes a new SELECT_ONE commitment against the customer's explicitly selected eligible instance. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Invocation invokeSelected(String capabilityId, String operationName, Map<String, String> scope,
            UUID selectedInstanceId, String idempotencyKey, Object payload) {
        if (selectedInstanceId == null) throw new IllegalArgumentException("A selected provider instance is required");
        return invokeRouted(capabilityId, operationName, scope, null, selectedInstanceId, true,
                idempotencyKey, payload);
    }

    private Invocation invokeRouted(String capabilityId, String operationName, Map<String, String> scope,
            UUID pinnedInstanceId, UUID selectedInstanceId, boolean newCommitment, String idempotencyKey, Object payload) {
        var operation = CapabilityRegistry.find(capabilityId).flatMap(c -> c.operation(operationName))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Core capability operation"));
        Map<String, String> normalizedScope = scope == null ? Map.of() : Map.copyOf(scope);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
            throw new IllegalArgumentException("A bounded idempotency key is required");
        if (!operation.sideEffecting() && operation.policy() != CapabilityRegistry.Policy.PRIMARY_REPLICAS) {
            if (operation.policy() != CapabilityRegistry.Policy.PINNED_ONE || pinnedInstanceId == null)
                throw new IllegalArgumentException("Only pinned read operations may use this invocation path");
            return invokePinnedRead(capabilityId, operationName, normalizedScope, pinnedInstanceId, idempotencyKey, payload);
        }
        if (operation.policy() == CapabilityRegistry.Policy.AGGREGATE_READ
                || operation.policy() == CapabilityRegistry.Policy.EXCLUSIVE) {
            throw new IllegalArgumentException("Operation policy is not supported by the durable invocation path");
        }
        String payloadJson = write(payload);
        String requestJson = write(new Fingerprint(capabilityId, operationName, new java.util.TreeMap<>(normalizedScope),
                pinnedInstanceId, selectedInstanceId, payload));
        String fingerprint = sha256(requestJson);
        Attempt existing = findAttempt(capabilityId, operationName, idempotencyKey);
        List<Target> targets;
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
            if ("SUCCEEDED".equals(existing.state())) return new Invocation(pluginPayload(existing.resultJson()), existing.owner());
            if ("UNKNOWN".equals(existing.state()) || "RECONCILING".equals(existing.state())
                    || "IN_PROGRESS".equals(existing.state()) && existing.leaseUntil() != null
                    && existing.leaseUntil().isAfter(java.time.Instant.now())) throw new OutcomeUnknownException(existing.owner());
            Plan existingPlan = readPlan(existing.planJson());
            targets = existingPlan.targets();
            normalizedScope = existingPlan.scope();
            payloadJson = encryption.decrypt(existing.requestSnapshot());
        } else {
            Attempt placement = placementAnchor(capabilityId, operationName, payloadJson, idempotencyKey);
            if (placement != null) {
                if ("UNKNOWN".equals(placement.state()) || "RECONCILING".equals(placement.state()) || "IN_PROGRESS".equals(placement.state()))
                    throw new OutcomeUnknownException(placement.owner());
                Plan frozenPlacement = readPlan(placement.planJson());
                targets = frozenPlacement.targets();
                normalizedScope = frozenPlacement.scope();
            } else {
                List<BindingRow> rows = bindings(capabilityId, operationName);
                if (rows.isEmpty()) return null;
                List<CapabilityRouter.Binding> candidates = rows.stream().map(BindingRow::binding).toList();
                CapabilityRouter.RoutePlan route = new CapabilityRouter().resolve(new CapabilityRouter.RouteRequest(
                        capabilityId, operationName, normalizedScope, pinnedInstanceId, selectedInstanceId, newCommitment), candidates);
                targets = route.bindingIds().stream().map(bindingId -> rows.stream()
                        .filter(row -> row.binding().bindingId().equals(bindingId)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Selected capability binding disappeared")))
                        .map(this::target).toList();
            }
        }
        Target target = targets.getFirst();
        UUID attemptId;
        if (existing == null) {
            attemptId = UUID.randomUUID();
            String permissions = write(List.of(operation.requiredPermission()));
            String plan = write(new Plan(capabilityId, operationName, operation.policy().name(), operation.sideEffecting(), normalizedScope, targets));
            int inserted = jdbc.update("INSERT INTO extension_operation_attempts(attempt_id,binding_id,instance_id,plugin_id,release_id,"
                            + "configuration_generation,external_account_fingerprint,capability_id,operation_name,scope_snapshot,permissions_snapshot,"
                            + "idempotency_key,request_fingerprint,request_snapshot,correlation_id,deadline_at,plan_snapshot) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?::jsonb) ON CONFLICT (capability_id,operation_name,idempotency_key) DO NOTHING",
                    attemptId, target.bindingId(), target.instanceId(), target.pluginId(), target.releaseId(), target.configurationGeneration(),
                    target.externalAccountFingerprint(), capabilityId, operationName, write(new java.util.TreeMap<>(normalizedScope)), permissions,
                    idempotencyKey, fingerprint, encryption.encrypt(payloadJson), UUID.randomUUID(), OffsetDateTime.now().plus(DEADLINE), plan);
            if (inserted == 0) {
                existing = findAttempt(capabilityId, operationName, idempotencyKey);
                if (existing == null || !existing.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
                if ("SUCCEEDED".equals(existing.state())) return new Invocation(pluginPayload(existing.resultJson()), existing.owner());
                if ("UNKNOWN".equals(existing.state()) || "RECONCILING".equals(existing.state()) || "IN_PROGRESS".equals(existing.state()))
                    throw new OutcomeUnknownException(existing.owner());
                attemptId = existing.attemptId();
                Plan existingPlan = readPlan(existing.planJson()); targets = existingPlan.targets(); target = targets.getFirst();
                normalizedScope = existingPlan.scope(); payloadJson = encryption.decrypt(existing.requestSnapshot());
            }
        } else {
            attemptId = existing.attemptId();
            payloadJson = encryption.decrypt(existing.requestSnapshot());
        }
        UUID lease = UUID.randomUUID();
        if (jdbc.update("UPDATE extension_operation_attempts SET attempt_state='IN_PROGRESS',lease_token=?,lease_until=now()+interval '45 seconds',"
                        + "attempt_count=attempt_count+1,updated_at=now() WHERE attempt_id=? AND (attempt_state IN ('PENDING','FAILED') "
                        + "OR (attempt_state='IN_PROGRESS' AND lease_until<now()))", lease, attemptId) == 0) {
            Attempt latest = findAttempt(capabilityId, operationName, idempotencyKey);
            throw new OutcomeUnknownException(latest.owner());
        }
        try {
            ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Docker extension runtime is disabled");
            Map<String, Object> config = new HashMap<>(target.publicConfiguration());
            target.encryptedSecrets().forEach((key, value) -> config.put(key, encryption.decrypt(value)));
            var response = invokeStorageTargets(runtime, targets, capabilityId, operationName, idempotencyKey, payloadJson, normalizedScope);
            String resultSnapshot = "storage.object.v1".equals(capabilityId) && "get".equals(operationName)
                    ? write(Map.of("encryptedReadResult", encryption.encrypt(write(response)))) : write(response);
            jdbc.update("UPDATE extension_operation_attempts SET attempt_state='SUCCEEDED',result_snapshot=?::jsonb,last_error_code=NULL,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                    resultSnapshot, attemptId, lease);
            return new Invocation(response, target.owner(normalizedScope));
        } catch (OutcomeUnknownException failure) {
            throw failure;
        } catch (ExtensionRuntimePort.ExtensionCallException failure) {
            if ("createCheckout".equals(operationName) && switch (failure.statusCode()) {
                case 400, 401, 403, 404, 422 -> true; default -> false;
            }) {
                jdbc.update("UPDATE extension_operation_attempts SET attempt_state='FAILED',last_error_code=?,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?",
                        "PROVIDER_REJECTED_" + failure.statusCode(), attemptId, lease);
                throw new io.artio.extensions.commerce.PaymentProvider.CheckoutRejectedException("PROVIDER_REJECTED_" + failure.statusCode());
            }
            jdbc.update("UPDATE extension_operation_attempts SET attempt_state='UNKNOWN',last_error_code='PLUGIN_OUTCOME_UNKNOWN',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?", attemptId, lease);
            throw new OutcomeUnknownException(target.owner(normalizedScope), failure);
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE extension_operation_attempts SET attempt_state='UNKNOWN',last_error_code='PLUGIN_OUTCOME_UNKNOWN',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE attempt_id=? AND lease_token=?", attemptId, lease);
            throw new OutcomeUnknownException(target.owner(normalizedScope), failure);
        }
    }

    private Attempt placementAnchor(String capabilityId, String operationName, String payloadJson, String idempotencyKey) {
        if (!"storage.object.v1".equals(capabilityId)) return null;
        try {
            var payload = mapper.readTree(payloadJson);
            String routingKey = payload.path("routingKey").asText(null);
            if (routingKey == null || routingKey.isBlank()) {
                if ("put".equals(operationName)) return null;
                throw new IllegalStateException("Object storage operation is missing its historical placement identity");
            }
            if ("put".equals(operationName) && routingKey.equals(idempotencyKey)) return null;
            Attempt anchor = findAttempt(capabilityId, "put", routingKey);
            if (anchor == null) throw new IllegalStateException("Object storage placement identity was not found; fallback routing is forbidden");
            return anchor;
        } catch (java.io.IOException malformed) {
            throw new IllegalArgumentException("Invalid object storage request", malformed);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode invokeStorageTargets(ExtensionRuntimePort runtime, List<Target> targets,
            String capabilityId, String operationName, String idempotencyKey, String payloadJson, Map<String, String> scope) {
        if (!"storage.object.v1".equals(capabilityId)) {
            Target target = targets.getFirst();
            var result = call(runtime, target, capabilityId, operationName, idempotencyKey, payloadJson);
            if (result.statusCode() < 200 || result.statusCode() >= 300) throw new OutcomeUnknownException(target.owner(scope));
            return parseJson(result.responseJson());
        }
        if (targets.isEmpty()) throw new IllegalStateException("Object storage route has no targets");
        List<com.fasterxml.jackson.databind.JsonNode> receipts = new java.util.ArrayList<>();
        List<String> targetInstanceIds = new java.util.ArrayList<>();
        RuntimeException primaryFailure = null;
        String expectedChecksum = null;
        int expectedChunkIndex = -1;
        if ("get".equals(operationName)) {
            var request = parseJson(payloadJson);
            expectedChecksum = request.path("expectedChecksum").asText(null);
            expectedChunkIndex = request.path("chunkIndex").asInt(-1);
        }
        for (int index = 0; index < targets.size(); index++) {
            Target target = targets.get(index);
            String role = index == 0 ? "PRIMARY" : "REPLICA";
            String request = withPlacementRole(payloadJson, role);
            try {
                var result = call(runtime, target, capabilityId, operationName, idempotencyKey, request);
                if (result.statusCode() < 200 || result.statusCode() >= 300)
                    throw new ExtensionRuntimePort.ExtensionCallException(result.statusCode());
                var receipt = parseJson(result.responseJson());
                if ("get".equals(operationName) && (expectedChecksum == null || !expectedChecksum.equalsIgnoreCase(receipt.path("sha256").asText())
                        || expectedChunkIndex != receipt.path("chunkIndex").asInt(-1)))
                    throw new IllegalStateException("Object storage replica returned an unverified media chunk");
                receipts.add(receipt);
                targetInstanceIds.add(target.instanceId().toString());
            } catch (RuntimeException failure) {
                if ("get".equals(operationName) || "head".equals(operationName)) {
                    primaryFailure = failure;
                    continue;
                }
                throw new OutcomeUnknownException(target.owner(scope), failure);
            }
            if ("get".equals(operationName) || "head".equals(operationName)) {
                var response = receipts.getLast().deepCopy();
                if (response.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) response)
                        .put("servedByInstanceId", target.instanceId().toString());
                return response;
            }
        }
        if (receipts.isEmpty()) throw new OutcomeUnknownException(targets.getFirst().owner(scope), primaryFailure);
        Map<String, Object> result = new HashMap<>();
        result.put("receipts", receipts);
        result.put("targetInstanceIds", targetInstanceIds);
        result.put("primaryInstanceId", targets.getFirst().instanceId());
        result.put("allReplicasAcknowledged", receipts.size() == targets.size());
        return mapper.valueToTree(result);
    }

    private ExtensionRuntimePort.InvocationResult call(ExtensionRuntimePort runtime, Target target, String capability,
            String operation, String idempotencyKey, String payloadJson) {
        Map<String, Object> config = new HashMap<>(target.publicConfiguration());
        target.encryptedSecrets().forEach((key, value) -> config.put(key, encryption.decrypt(value)));
        return runtime.invoke(target.runtimeIdentity(), target.runtimeCredentialGeneration(), capability,
                operation, idempotencyKey, write(config), payloadJson, DEADLINE);
    }

    private String withPlacementRole(String payloadJson, String role) {
        try {
            var node = mapper.readTree(payloadJson);
            if (!node.isObject()) throw new IllegalArgumentException("Object storage payload must be an object");
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("placementRole", role);
            return mapper.writeValueAsString(node);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("Invalid object storage payload", invalid); }
    }

    private List<BindingRow> bindings(String capability, String operation) {
        return jdbc.query("SELECT b.binding_id,b.instance_id,b.capability_id,b.operation_name,b.scope::text AS scope,b.priority,b.eligibility_state,"
                        + "i.lifecycle_state,i.release_id FROM extension_capability_bindings b JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "WHERE b.capability_id=? AND b.operation_name=?",
                (rs, row) -> {
                    long releaseId = rs.getLong("release_id");
                    Set<String> permissions = jdbc.query("SELECT manifest::text FROM extension_plugin_v2_releases WHERE release_id=?",
                            result -> result.next() ? read(result.getString(1), PluginManifestV2.class).capabilities().stream()
                                    .filter(c -> c.capabilityId().equals(capability) && c.operations().contains(operation))
                                    .flatMap(c -> c.permissions().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.of(), releaseId);
                    String lifecycle = rs.getString("lifecycle_state");
                    var binding = new CapabilityRouter.Binding(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class),
                            rs.getString("capability_id"), rs.getString("operation_name"), read(rs.getString("scope"), STRINGS), rs.getInt("priority"),
                            permissions, CapabilityRouter.BindingState.valueOf(rs.getString("eligibility_state")), routerState(lifecycle), "ENABLED".equals(lifecycle));
                    return new BindingRow(binding);
                }, capability, operation);
    }

    public boolean hasEnabledBinding(String capabilityId, String operationName) {
        var operation = CapabilityRegistry.find(capabilityId).flatMap(c -> c.operation(operationName)).orElse(null);
        if (operation == null) return false;
        return bindings(capabilityId, operationName).stream().map(BindingRow::binding)
                .anyMatch(binding -> binding.state() == CapabilityRouter.BindingState.ACTIVE
                        && binding.instanceState() == CapabilityRouter.InstanceState.ENABLED && binding.healthy()
                        && binding.permissions().contains(operation.requiredPermission()));
    }

    /** Performs a bounded, side-effect-free read against every eligible binding. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public AggregateInvocation aggregateRead(String capabilityId, String operationName, Map<String, String> scope, Object payload) {
        return aggregateReadForInstance(capabilityId, operationName, scope, null, payload);
    }

    /** Performs a side-effect-free read against one explicitly selected eligible binding. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public AggregateInvocation aggregateReadForInstance(String capabilityId, String operationName, Map<String, String> scope,
            UUID selectedInstanceId, Object payload) {
        var operation = CapabilityRegistry.find(capabilityId).flatMap(c -> c.operation(operationName))
                .orElseThrow(() -> new IllegalArgumentException("Unsupported Core capability operation"));
        if (operation.policy() != CapabilityRegistry.Policy.AGGREGATE_READ || operation.sideEffecting())
            throw new IllegalArgumentException("Only registered side-effect-free aggregate reads may use this invocation path");
        Map<String, String> normalizedScope = scope == null ? Map.of() : Map.copyOf(scope);
        List<BindingRow> rows = bindings(capabilityId, operationName);
        if (rows.isEmpty()) return new AggregateInvocation(List.of(), List.of());
        CapabilityRouter.RoutePlan route = new CapabilityRouter().resolve(new CapabilityRouter.RouteRequest(
                capabilityId, operationName, normalizedScope, null, selectedInstanceId, false), rows.stream().map(BindingRow::binding).toList());
        if (route.bindingIds().size() > 32) throw new IllegalStateException("Aggregate provider count exceeds the safe request bound");
        List<Target> targets = route.bindingIds().stream().map(bindingId -> rows.stream()
                .filter(row -> row.binding().bindingId().equals(bindingId)).findFirst()
                .map(this::target).orElseThrow(() -> new IllegalStateException("Selected capability binding disappeared")))
                .toList();
        ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Docker extension runtime is disabled");
        String payloadJson = write(payload);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(8, targets.size()));
        try {
            List<java.util.concurrent.Callable<AggregateProviderResult>> tasks = targets.stream().<java.util.concurrent.Callable<AggregateProviderResult>>map(target -> () -> {
                String key = UUID.nameUUIDFromBytes((capabilityId + ":" + operationName + ":" + target.instanceId() + ":"
                        + sha256(write(new Fingerprint(capabilityId, operationName, new java.util.TreeMap<>(normalizedScope), null, payload))))
                        .getBytes(StandardCharsets.UTF_8)).toString();
                try {
                    Map<String, Object> config = new HashMap<>(target.publicConfiguration());
                    target.encryptedSecrets().forEach((name, secret) -> config.put(name, encryption.decrypt(secret)));
                    var response = runtime.invoke(target.runtimeIdentity(), target.runtimeCredentialGeneration(), capabilityId,
                            operationName, key, write(config), payloadJson, DEADLINE);
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        return AggregateProviderResult.failure(target, "HTTP_" + response.statusCode());
                    var json = mapper.readTree(response.responseJson());
                    if (json == null || !json.isObject()) return AggregateProviderResult.failure(target, "INVALID_RESPONSE");
                    return AggregateProviderResult.success(target, json);
                } catch (Exception failure) {
                    return AggregateProviderResult.failure(target, safeProviderFailure(failure));
                }
            }).toList();
            List<java.util.concurrent.Future<AggregateProviderResult>> futures = executor.invokeAll(tasks, DEADLINE.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            List<AggregateProviderResult> results = new java.util.ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index);
                if (future.isCancelled()) results.add(AggregateProviderResult.failure(targets.get(index), "TIMEOUT"));
                else try { results.add(future.get()); }
                catch (Exception failure) { results.add(AggregateProviderResult.failure(targets.get(index), safeProviderFailure(failure))); }
            }
            return new AggregateInvocation(results.stream().filter(AggregateProviderResult::successful).toList(),
                    results.stream().filter(result -> !result.successful()).toList());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Aggregate provider discovery was interrupted");
        } finally { executor.shutdownNow(); }
    }

    private static String safeProviderFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String code = cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
        return code.isBlank() ? "PROVIDER_UNAVAILABLE" : code.substring(0, Math.min(64, code.length()));
    }

    private Invocation invokePinnedRead(String capability, String operation, Map<String, String> scope,
            UUID instanceId, String idempotencyKey, Object payload) {
        List<BindingRow> rows = bindings(capability, operation);
        CapabilityRouter.RoutePlan route = new CapabilityRouter().resolve(new CapabilityRouter.RouteRequest(
                capability, operation, scope, instanceId, false), rows.stream().map(BindingRow::binding).toList());
        BindingRow selected = rows.stream().filter(row -> row.binding().bindingId().equals(route.bindingIds().getFirst()))
                .findFirst().orElseThrow(() -> new CapabilityRouter.RoutingException("Historical provider binding is unavailable"));
        Target target = target(selected);
        ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
        if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Docker extension runtime is disabled");
        Map<String, Object> config = new HashMap<>(target.publicConfiguration());
        target.encryptedSecrets().forEach((key, value) -> config.put(key, encryption.decrypt(value)));
        try {
            var result = runtime.invoke(target.runtimeIdentity(), target.runtimeCredentialGeneration(), capability,
                    operation, idempotencyKey, write(config), write(payload), DEADLINE);
            if (result.statusCode() < 200 || result.statusCode() >= 300)
                throw new ExtensionRuntimePort.ExtensionCallException(result.statusCode());
            return new Invocation(mapper.readTree(result.responseJson()), new Owner(target.instanceId(), target.releaseId(),
                    target.configurationGeneration(), target.externalAccountFingerprint(), scope));
        } catch (java.io.IOException invalid) {
            throw new ExtensionRuntimePort.ExtensionProtocolException("Plugin returned an invalid response");
        }
    }

    private Target target(BindingRow selected) {
        return jdbc.query("SELECT b.binding_id,b.instance_id,i.plugin_id,i.runtime_identity,i.release_id,i.configuration_generation,"
                        + "i.external_account_fingerprint,i.public_configuration::text AS public_configuration,i.encrypted_secrets::text AS encrypted_secrets,"
                        + "p.credential_generation FROM extension_capability_bindings b JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "JOIN extension_plugins p ON p.plugin_id=i.plugin_id WHERE b.binding_id=? AND b.instance_id=?",
                rs -> rs.next() ? new Target(rs.getObject("binding_id", UUID.class), rs.getObject("instance_id", UUID.class), rs.getString("plugin_id"),
                        rs.getString("runtime_identity"), rs.getLong("release_id"), rs.getInt("configuration_generation"), rs.getInt("credential_generation"),
                        trimToNull(rs.getString("external_account_fingerprint")),
                        read(rs.getString("public_configuration"), OBJECTS), read(rs.getString("encrypted_secrets"), STRINGS)) : null,
                selected.binding().bindingId(), selected.binding().instanceId());
    }

    private Attempt findAttempt(String capability, String operation, String key) {
        return jdbc.query("SELECT attempt_id,request_fingerprint,attempt_state,plan_snapshot::text AS plan_snapshot,request_snapshot,result_snapshot::text AS result_snapshot,lease_until FROM extension_operation_attempts WHERE capability_id=? AND operation_name=? AND idempotency_key=?",
                rs -> rs.next() ? new Attempt(rs.getObject("attempt_id", UUID.class), rs.getString("request_fingerprint").trim(), rs.getString("attempt_state"),
                        rs.getString("plan_snapshot"), rs.getString("request_snapshot"), rs.getString("result_snapshot"),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                        readPlan(rs.getString("plan_snapshot")).owner()) : null,
                capability, operation, key);
    }

    private Plan readPlan(String json) { return read(json, Plan.class); }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception failure) { throw new IllegalArgumentException("Invalid plugin operation payload", failure); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception failure) { throw new IllegalStateException("Invalid persisted plugin operation snapshot", failure); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return mapper.readValue(value, type); } catch (Exception failure) { throw new IllegalStateException("Invalid persisted plugin operation snapshot", failure); }
    }
    private com.fasterxml.jackson.databind.JsonNode parseJson(String value) {
        try { return mapper.readTree(value); } catch (Exception failure) { throw new IllegalStateException("Invalid persisted plugin result", failure); }
    }
    private com.fasterxml.jackson.databind.JsonNode pluginPayload(String value) {
        var saved = parseJson(value);
        String encryptedReadResult = saved.path("encryptedReadResult").asText(null);
        if (encryptedReadResult != null) saved = parseJson(encryption.decrypt(encryptedReadResult));
        var results = saved.path("results");
        if ("PRIMARY_REPLICAS".equals(saved.path("policy").asText()) && results.isArray()) {
            List<com.fasterxml.jackson.databind.JsonNode> receipts = new java.util.ArrayList<>();
            List<String> targetInstanceIds = new java.util.ArrayList<>();
            boolean acknowledged = true;
            String primaryInstanceId = null;
            for (com.fasterxml.jackson.databind.JsonNode item : results) {
                if (primaryInstanceId == null) primaryInstanceId = item.path("instanceId").asText(null);
                int status = item.path("statusCode").asInt(500);
                acknowledged &= status >= 200 && status < 300;
                if (status >= 200 && status < 300) receipts.add(item.path("payload"));
                if (status >= 200 && status < 300) targetInstanceIds.add(item.path("instanceId").asText());
            }
            Map<String, Object> aggregate = new HashMap<>();
            aggregate.put("receipts", receipts);
            aggregate.put("targetInstanceIds", targetInstanceIds);
            aggregate.put("allReplicasAcknowledged", acknowledged && !results.isEmpty() && receipts.size() == results.size());
            if (primaryInstanceId != null) aggregate.put("primaryInstanceId", primaryInstanceId);
            return mapper.valueToTree(aggregate);
        }
        if (results.isArray() && !results.isEmpty()) return results.get(0).path("payload");
        return saved;
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static String trimToNull(String value) { return value == null ? null : value.trim(); }
    private static CapabilityRouter.InstanceState routerState(String state) {
        return switch (state) { case "ENABLED" -> CapabilityRouter.InstanceState.ENABLED; case "DRAINING" -> CapabilityRouter.InstanceState.DRAINING;
            case "UNHEALTHY" -> CapabilityRouter.InstanceState.UNHEALTHY; case "FAILED" -> CapabilityRouter.InstanceState.FAILED;
            default -> CapabilityRouter.InstanceState.DISABLED; };
    }

    public record Owner(UUID instanceId, Long releaseId, int configurationGeneration, String externalAccountFingerprint,
                       Map<String, String> scope) {
        public Owner { scope = Map.copyOf(scope); }
    }
    public record Invocation(com.fasterxml.jackson.databind.JsonNode response, Owner owner) { }
    public record AggregateInvocation(List<AggregateProviderResult> results, List<AggregateProviderResult> failures) {
        public AggregateInvocation { results = List.copyOf(results); failures = List.copyOf(failures); }
        public boolean partialFailure() { return !failures.isEmpty(); }
    }
    public record AggregateProviderResult(UUID instanceId, String pluginId, long releaseId, int configurationGeneration,
            com.fasterxml.jackson.databind.JsonNode response, String errorCode) {
        private static AggregateProviderResult success(Target target, com.fasterxml.jackson.databind.JsonNode response) {
            return new AggregateProviderResult(target.instanceId(), target.pluginId(), target.releaseId(),
                    target.configurationGeneration(), response, null);
        }
        private static AggregateProviderResult failure(Target target, String code) {
            return new AggregateProviderResult(target.instanceId(), target.pluginId(), target.releaseId(),
                    target.configurationGeneration(), null, code);
        }
        public boolean successful() { return response != null; }
    }
    private record Fingerprint(String capabilityId, String operationName, Map<String, String> scope,
            UUID pinnedInstanceId, UUID selectedInstanceId, Object payload) {
        private Fingerprint(String capabilityId, String operationName, Map<String, String> scope,
                UUID pinnedInstanceId, Object payload) {
            this(capabilityId, operationName, scope, pinnedInstanceId, null, payload);
        }
    }
    private record BindingRow(CapabilityRouter.Binding binding) { }
    private record Attempt(UUID attemptId, String fingerprint, String state, String planJson, String requestSnapshot,
            String resultJson, java.time.Instant leaseUntil, Owner owner) { }
    private record Plan(String capabilityId, String operationName, String policy, boolean sideEffecting,
            Map<String, String> scope, List<Target> targets) {
        private Plan { scope = Map.copyOf(scope); targets = List.copyOf(targets); }
        private Owner owner() { Target target = targets.getFirst(); return target.owner(scope); }
    }
    private record Target(UUID bindingId, UUID instanceId, String pluginId, String runtimeIdentity, long releaseId,
            int configurationGeneration, int runtimeCredentialGeneration, String externalAccountFingerprint,
            Map<String, Object> publicConfiguration, Map<String, String> encryptedSecrets) {
        private Target { publicConfiguration = Map.copyOf(publicConfiguration); encryptedSecrets = Map.copyOf(encryptedSecrets); }
        private Owner owner(Map<String, String> scope) { return new Owner(instanceId, releaseId, configurationGeneration, externalAccountFingerprint, scope); }
    }
    public static final class IdempotencyConflictException extends IllegalStateException { public IdempotencyConflictException() { super("Idempotency key is already bound to a different capability request"); } }
    public static final class OutcomeUnknownException extends IllegalStateException {
        private final Owner owner;
        public OutcomeUnknownException(Owner owner) { super("Plugin operation outcome is unknown; retry or reconcile with the original owner"); this.owner = owner; }
        public OutcomeUnknownException(Owner owner, Throwable cause) { super("Plugin operation outcome is unknown; retry or reconcile with the original owner", cause); this.owner = owner; }
        public Owner owner() { return owner; }
    }
}
