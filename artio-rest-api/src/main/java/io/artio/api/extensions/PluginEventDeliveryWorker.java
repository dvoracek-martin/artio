package io.artio.api.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.ExtensionRuntimePort;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Core-owned at-least-once dispatcher for immutable, inbox-transaction event deliveries. */
@Component
@ConditionalOnProperty(prefix = "artio.workers", name = "enabled", havingValue = "true")
public class PluginEventDeliveryWorker {
    private static final TypeReference<Map<String, Object>> PUBLIC_CONFIG = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> ENCRYPTED_SECRETS = new TypeReference<>() { };
    private static final int BATCH_SIZE = 32;
    private static final int MAX_ATTEMPTS = 12;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EncryptionService encryption;
    private final ObjectProvider<ExtensionRuntimePort> runtimeProvider;

    public PluginEventDeliveryWorker(JdbcTemplate jdbc, ObjectMapper mapper, EncryptionService encryption,
            ObjectProvider<ExtensionRuntimePort> runtimeProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.encryption = encryption;
        this.runtimeProvider = runtimeProvider;
    }

    @Scheduled(fixedDelayString = "${artio.extensions.events.poll-interval-ms:1000}")
    public void deliverPending() {
        List<Delivery> deliveries = jdbc.query("""
                UPDATE extension_event_deliveries SET state='IN_PROGRESS', attempt_count=attempt_count+1,
                    lease_token=md5(delivery_id::text || clock_timestamp()::text || random()::text)::uuid,
                    lease_until=now()+interval '45 seconds', updated_at=now()
                WHERE delivery_id IN (
                    SELECT delivery_id FROM extension_event_deliveries
                    WHERE (state IN ('PENDING','RETRY') AND available_at<=now())
                       OR (state='IN_PROGRESS' AND lease_until<now())
                    ORDER BY available_at,created_at LIMIT ? FOR UPDATE SKIP LOCKED
                )
                RETURNING delivery_id,plugin_id,runtime_identity,configuration_generation,runtime_credential_generation,
                    public_configuration_snapshot::text,encrypted_secrets_snapshot::text,payload_snapshot::text,
                    attempt_count,lease_token
                """, (rs, row) -> new Delivery(rs.getObject("delivery_id", UUID.class), rs.getString("plugin_id"),
                rs.getString("runtime_identity"), rs.getInt("configuration_generation"), rs.getInt("runtime_credential_generation"),
                rs.getString("public_configuration_snapshot"), rs.getString("encrypted_secrets_snapshot"),
                rs.getString("payload_snapshot"), rs.getInt("attempt_count"), rs.getObject("lease_token", UUID.class)), BATCH_SIZE);
        for (Delivery delivery : deliveries) dispatch(delivery);
    }

    private void dispatch(Delivery delivery) {
        try {
            ExtensionRuntimePort runtime = runtimeProvider.getIfAvailable();
            if (runtime == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Plugin runtime is unavailable");
            Map<String, Object> configuration = new HashMap<>(mapper.readValue(delivery.publicConfiguration(), PUBLIC_CONFIG));
            mapper.readValue(delivery.encryptedSecrets(), ENCRYPTED_SECRETS)
                    .forEach((key, value) -> configuration.put(key, encryption.decrypt(value)));
            var result = runtime.invoke(delivery.runtimeIdentity(), delivery.runtimeCredentialGeneration(),
                    "events.subscriber.v1", "deliver", delivery.deliveryId().toString(),
                    mapper.writeValueAsString(configuration), delivery.payload(), Duration.ofSeconds(10));
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw new ExtensionRuntimePort.ExtensionCallException(result.statusCode());
            }
            var acknowledgement = mapper.readTree(result.responseJson());
            if (acknowledgement == null || !acknowledgement.isObject()
                    || !acknowledgement.path("accepted").isBoolean() || !acknowledgement.path("accepted").asBoolean()
                    || !delivery.deliveryId().toString().equals(acknowledgement.path("deliveryId").asText(null))) {
                throw new ExtensionRuntimePort.ExtensionProtocolException("Plugin did not acknowledge the stable event delivery identity");
            }
            jdbc.update("UPDATE extension_event_deliveries SET state='DELIVERED',lease_token=NULL,lease_until=NULL,"
                    + "last_error_code=NULL,updated_at=now() WHERE delivery_id=? AND state='IN_PROGRESS' AND lease_token=?",
                    delivery.deliveryId(), delivery.leaseToken());
        } catch (Exception failure) {
            String errorCode = failure.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", "");
            if (errorCode.length() > 64) errorCode = errorCode.substring(0, 64);
            boolean dead = delivery.attemptCount() >= MAX_ATTEMPTS;
            long delay = Math.min(3600L, 1L << Math.min(delivery.attemptCount(), 12));
            jdbc.update("UPDATE extension_event_deliveries SET state=?,available_at=now()+(? * interval '1 second'),"
                    + "lease_token=NULL,lease_until=NULL,last_error_code=?,updated_at=now() "
                    + "WHERE delivery_id=? AND state='IN_PROGRESS' AND lease_token=?",
                    dead ? "DEAD_LETTER" : "RETRY", dead ? 0 : delay, errorCode, delivery.deliveryId(), delivery.leaseToken());
        }
    }

    private record Delivery(UUID deliveryId, String pluginId, String runtimeIdentity, int configurationGeneration,
            int runtimeCredentialGeneration,
            String publicConfiguration, String encryptedSecrets, String payload, int attemptCount, UUID leaseToken) { }
}
