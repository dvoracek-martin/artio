package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.artio.extensions.commerce.NotificationTransport.DeliveryResult;
import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import io.artio.api.extensions.PluginEventDeliveryWorker;
import io.artio.api.extensions.PluginEventBackfillOperations;
import io.artio.api.security.EncryptionService;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises broker delivery, transactional inbox deduplication and durable notification enqueue. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderCreatedNotificationConsumerIntegrationTests {
    private static final String TOPIC = "artio.order.events";
    private static final String DLQ = TOPIC + ".dlq";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired EncryptionService encryption;
    @Autowired PluginEventBackfillOperations eventBackfillOperations;

    @BeforeEach
    void clearPluginEventFixtures() {
        // Package releases are append-only; delete only the per-test mutable runtime and delivery rows.
        jdbc.update("DELETE FROM extension_event_deliveries WHERE plugin_id LIKE 'events.%' OR plugin_id LIKE 'backfill.%'");
        jdbc.update("DELETE FROM extension_event_backfill_runs WHERE plugin_id LIKE 'events.%' OR plugin_id LIKE 'backfill.%'");
        jdbc.update("DELETE FROM extension_event_subscriptions WHERE instance_id IN "
                + "(SELECT instance_id FROM extension_plugin_instances WHERE plugin_id LIKE 'events.%' OR plugin_id LIKE 'backfill.%')");
        jdbc.update("DELETE FROM extension_capability_bindings WHERE instance_id IN "
                + "(SELECT instance_id FROM extension_plugin_instances WHERE plugin_id LIKE 'events.%' OR plugin_id LIKE 'backfill.%')");
        jdbc.update("DELETE FROM extension_plugin_instances WHERE plugin_id LIKE 'events.%' OR plugin_id LIKE 'backfill.%'");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> "integration-test-encryption-key-value-32-bytes-minimum");
        registry.add("artio.setup.token", () -> "integration-test-setup-token-value-32-bytes-minimum");
        registry.add("artio.events.kafka.consumer-enabled", () -> "true");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.workers.enabled", () -> "false");
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(java.util.List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLQ, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void consumesOrderCreatedOnceAndEnqueuesLocalizedEmailDurably() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "event-consumer-" + userId + "@example.test", "unused-test-hash", "Ada", "Lovelace");

        String json = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                "type", "ORDER_CREATED", "occurredAt", Instant.now(), "orderId", orderId,
                "userId", userId, "locale", "de-CH"));
        send(eventId, orderId, json);
        awaitInbox(eventId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM notification_delivery_outbox WHERE event_key=?", String.class,
                "order-email:" + orderId)).isEqualTo("Bestellung eingegangen");

        send(eventId, orderId, json);
        Thread.sleep(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE consumer_name='order-notification-v1' AND event_id=?", Integer.class,
                eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + orderId)).isEqualTo(1);
    }

    @Test
    void freezesEnabledV2SubscriptionsAndSafePayloadInTheInboxTransaction() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID secondInstanceId = UUID.randomUUID();
        UUID secondSubscriptionId = UUID.randomUUID();
        String pluginId = "events." + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String image = "ghcr.io/example/events@sha256:" + "a".repeat(64);
        PluginManifest packageManifest = packageManifest(pluginId, image);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state,public_configuration) "
                        + "VALUES (?,?::jsonb,?,'ENABLED',?::jsonb)", pluginId,
                mapper.writeValueAsString(packageManifest), image, "{\"region\":\"eu\"}");
        long releaseId = jdbc.queryForObject("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) "
                        + "VALUES (?, '2.0.0', ?, ?::jsonb) RETURNING release_id", Long.class, pluginId,
                image, mapper.writeValueAsString(packageManifest));
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?, ?, ?::jsonb)",
                releaseId, pluginId, v2Manifest(packageManifest));
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,public_configuration,encrypted_secrets) VALUES (?,?,'main',?,'CAPABILITY_V2',"
                        + "'ENABLED',?,?::jsonb,?::jsonb)", instanceId, pluginId, releaseId, pluginId, "{\"region\":\"eu\"}",
                "{\"apiKey\":\"" + encryption.encrypt("event-secret") + "\"}");
        jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,subscription_state) "
                        + "VALUES (?,?,'order.created',1,'ACTIVE')", subscriptionId, instanceId);
        String secondRuntimeIdentity = "instance-" + secondInstanceId.toString().replace("-", "");
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,configuration_generation,public_configuration,encrypted_secrets) "
                        + "VALUES (?,?,'eu-copy',?,'CAPABILITY_V2','ENABLED',?,1,'{\"region\":\"na\"}'::jsonb,?::jsonb)",
                secondInstanceId, pluginId, releaseId, secondRuntimeIdentity,
                "{\"apiKey\":\"" + encryption.encrypt("second-event-secret") + "\"}");
        jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,subscription_state) "
                        + "VALUES (?,?,'order.created',1,'ACTIVE')", secondSubscriptionId, secondInstanceId);
        Integer activeSubscriptions = jdbc.queryForObject("SELECT count(*) FROM extension_event_subscriptions s "
                        + "JOIN extension_plugin_instances i ON i.instance_id=s.instance_id "
                        + "JOIN extension_plugins p ON p.plugin_id=i.plugin_id "
                        + "WHERE i.plugin_id=? AND s.event_type='order.created' AND s.schema_version=1 "
                        + "AND s.subscription_state='ACTIVE' AND i.compatibility_mode='CAPABILITY_V2' AND i.lifecycle_state='ENABLED'",
                Integer.class, pluginId);
        assertThat(activeSubscriptions).as("Enabled V2 subscriptions must be eligible before publishing the event").isEqualTo(2);
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "event-fanout-" + userId + "@example.test", "unused-test-hash", "Event", "Fanout");

        String json = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                "type", "ORDER_CREATED", "occurredAt", Instant.now(), "orderId", orderId,
                "userId", userId, "locale", "en", "customerEmail", "should-not-leak@example.test"));
        send(eventId, orderId, json);
        awaitInbox(eventId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=?", Integer.class, eventId))
                .isEqualTo(2);
        var deliveries = jdbc.queryForList("SELECT delivery_id,runtime_identity,payload_snapshot::text AS payload,"
                + "public_configuration_snapshot->>'region' AS region FROM extension_event_deliveries WHERE inbox_event_id=? ORDER BY runtime_identity", eventId);
        UUID deliveryId = (UUID) deliveries.get(0).get("delivery_id");
        assertThat(deliveries).extracting(row -> row.get("runtime_identity"))
                .containsExactlyInAnyOrder(pluginId, secondRuntimeIdentity);
        assertThat(deliveries).allSatisfy(row -> {
            assertThat(row.get("payload").toString()).contains("order.created", orderId.toString())
                    .doesNotContain("customerEmail", "should-not-leak");
            assertThat(row.get("region")).isIn("eu", "na");
        });
        assertThat(jdbc.queryForObject("SELECT runtime_credential_generation FROM extension_event_deliveries WHERE delivery_id=?",
                Integer.class, deliveryId)).isEqualTo(1);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "UPDATE extension_event_deliveries SET payload_snapshot='{}'::jsonb WHERE delivery_id=?", deliveryId)))
                .hasMessageContaining("extension event delivery snapshots are immutable");

        var callsByKey = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var configurationsByRuntime = new java.util.concurrent.ConcurrentHashMap<String, String>();
        AtomicInteger calls = new AtomicInteger();
        ExtensionRuntimePort runtime = new ExtensionRuntimePort() {
            @Override public void install(io.artio.extensions.PluginManifest manifest) { }
            @Override public void enable(io.artio.extensions.PluginManifest manifest) { }
            @Override public void disable(String id) { }
            @Override public RuntimeHealth health(String id, String path) { return new RuntimeHealth(true, "OK"); }
            @Override public InvocationResult invoke(String id, String point, String operation, String key,
                    String configuration, String request, Duration deadline) {
                configurationsByRuntime.putIfAbsent(id, configuration);
                int deliveryCalls = callsByKey.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                calls.incrementAndGet();
                if (deliveryCalls == 1) throw new ExtensionUnavailableException("test transient failure");
                if (deliveryCalls == 2) return new InvocationResult(200, "{}");
                return new InvocationResult(200, "{\"accepted\":true,\"deliveryId\":\"" + key + "\"}");
            }
        };
        var beanFactory = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("runtime", runtime));
        var worker = new PluginEventDeliveryWorker(jdbc, mapper, encryption,
                beanFactory.getBeanProvider(ExtensionRuntimePort.class));
        worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=? AND state='RETRY'",
                Integer.class, eventId)).isEqualTo(2);
        jdbc.update("UPDATE extension_event_deliveries SET available_at=now() WHERE inbox_event_id=?", eventId);
        worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=? AND state='RETRY'",
                Integer.class, eventId)).isEqualTo(2);
        jdbc.update("UPDATE extension_event_deliveries SET available_at=now() WHERE inbox_event_id=?", eventId);
        worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=? AND state='DELIVERED'",
                Integer.class, eventId)).isEqualTo(2);
        assertThat(calls).hasValue(6);
        assertThat(callsByKey).hasSize(2).allSatisfy((key, value) -> assertThat(value).hasValue(3));
        assertThat(configurationsByRuntime.get(pluginId)).contains("event-secret");
        assertThat(configurationsByRuntime.get(secondRuntimeIdentity)).contains("second-event-secret");
        send(eventId, orderId, json);
        Thread.sleep(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=?", Integer.class, eventId))
                .isEqualTo(2);
    }

    @Test
    void previewsAndQueuesAuditedSubscriptionScopedBackfillAtABoundedRate() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        String pluginId = "backfill." + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        String image = "ghcr.io/example/events@sha256:" + "b".repeat(64);
        PluginManifest packageManifest = packageManifest(pluginId, image);
        Instant occurredAt = Instant.now().minusSeconds(60);
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                actorId, "event-backfill-" + actorId + "@example.test", "unused-test-hash", "Backfill", "Operator");
        String outboxPayload = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                "type", "ORDER_CREATED", "occurredAt", occurredAt, "orderId", orderId,
                "userId", actorId, "locale", "en"));
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload,status,published_at,created_at) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,?::jsonb,'PUBLISHED',?,?)",
                eventId, orderId.toString(), outboxPayload, java.sql.Timestamp.from(occurredAt), java.sql.Timestamp.from(occurredAt));
        String manifest = mapper.writeValueAsString(packageManifest);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state,public_configuration) "
                        + "VALUES (?,?::jsonb,?,'ENABLED','{}'::jsonb)", pluginId, manifest, image);
        long releaseId = jdbc.queryForObject("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) "
                        + "VALUES (?,'2.0.0',?,?::jsonb) RETURNING release_id", Long.class, pluginId, image, manifest);
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?, ?, ?::jsonb)",
                releaseId, pluginId, v2Manifest(packageManifest));
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,configuration_generation,public_configuration,encrypted_secrets) "
                        + "VALUES (?,?,'default',?,'CAPABILITY_V2','ENABLED',?,2,'{\"region\":\"eu\"}'::jsonb,?::jsonb)",
                instanceId, pluginId, releaseId, pluginId, "{\"token\":\"" + encryption.encrypt("backfill-secret") + "\"}");
        jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version,subscription_state) "
                + "VALUES (?,?,'order.created',1,'ACTIVE')", subscriptionId, instanceId);

        var preview = eventBackfillOperations.preview(subscriptionId, occurredAt.minusSeconds(30), occurredAt.plusSeconds(30),
                20, 2, actorId);
        assertThat(preview.eventCount()).isEqualTo(1);
        var queued = eventBackfillOperations.execute(preview.runId(), actorId);
        assertThat(queued.state()).isEqualTo("QUEUED");
        assertThat(queued.eventCount()).isEqualTo(1);
        assertThat(eventBackfillOperations.execute(preview.runId(), actorId)).isEqualTo(queued);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE backfill_run_id=? "
                        + "AND inbox_event_id=? AND subscription_id=? AND delivery_generation>0", Integer.class,
                preview.runId(), eventId, subscriptionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_type FROM extension_event_deliveries WHERE backfill_run_id=?",
                String.class, preview.runId())).isEqualTo("order.created");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_audit_events WHERE plugin_id=? "
                        + "AND action IN ('PREVIEW_EVENT_BACKFILL','EXECUTE_EVENT_BACKFILL') "
                        + "AND details->>'runId'=?", Integer.class, pluginId, preview.runId().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE consumer_name=? AND event_id=?",
                Integer.class, "plugin-backfill-" + preview.runId(), eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE backfill_run_id=?",
                String.class, preview.runId())).isEqualTo("PENDING");
        AtomicInteger backfillCalls = new AtomicInteger();
        ExtensionRuntimePort runtime = new ExtensionRuntimePort() {
            @Override public void install(io.artio.extensions.PluginManifest value) { }
            @Override public void enable(io.artio.extensions.PluginManifest value) { }
            @Override public void disable(String value) { }
            @Override public RuntimeHealth health(String value, String path) { return new RuntimeHealth(true, "OK"); }
            @Override public InvocationResult invoke(String value, String point, String operation, String key,
                    String configuration, String request, Duration deadline) {
                backfillCalls.incrementAndGet();
                assertThat(value).isEqualTo(pluginId);
                assertThat(request).contains(eventId.toString(), "order.created", orderId.toString());
                return new InvocationResult(200, "{\"accepted\":true,\"deliveryId\":\"" + key + "\"}");
            }
        };
        var runtimeFactory = new StaticListableBeanFactory(Map.of("runtime", runtime));
        new PluginEventDeliveryWorker(jdbc, mapper, encryption,
                runtimeFactory.getBeanProvider(ExtensionRuntimePort.class)).deliverPending();
        var delivery = jdbc.queryForMap("SELECT state,last_error_code,available_at FROM extension_event_deliveries WHERE backfill_run_id=?",
                preview.runId());
        assertThat(backfillCalls).as("Backfill delivery state: %s", delivery).hasValue(1);
        assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE backfill_run_id=?",
                String.class, preview.runId())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM extension_event_backfill_runs WHERE run_id=?",
                String.class, preview.runId())).isEqualTo("COMPLETE");
        jdbc.update("UPDATE extension_event_subscriptions SET subscription_state='DISABLED' WHERE subscription_id=?",
                subscriptionId);
    }

    @Test
    void sendsPoisonEventsToDeadLetterTopicAfterBoundedRetries() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", "dlq-verifier-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> dlqConsumer = new KafkaConsumer<>(properties)) {
            var partition = new TopicPartition(DLQ, 0);
            dlqConsumer.assign(java.util.List.of(partition));
            dlqConsumer.seekToEnd(java.util.List.of(partition));

            send(eventId, orderId, "not-json");
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            boolean deadLettered = false;
            while (Instant.now().isBefore(deadline) && !deadLettered) {
                var records = dlqConsumer.poll(Duration.ofMillis(250));
                deadLettered = records.records(partition).stream().anyMatch(record ->
                        record.key().equals(orderId.toString()) && "not-json".equals(record.value()));
            }
            assertThat(deadLettered).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE consumer_name='order-notification-v1' AND event_id=?",
                    Integer.class, eventId)).isZero();
        }
    }

    @Test
    void retriesOrderEventAfterPostgresConnectionOutageAndDoesNotDeadLetterIt() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "db-recovery-" + userId + "@example.test", "unused-test-hash", "Database", "Recovery");

        String json = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                "type", "ORDER_CREATED", "occurredAt", Instant.now(), "orderId", orderId,
                "userId", userId, "locale", "en"));

        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            String role = jdbc.queryForObject("SELECT current_user", String.class);
            String quotedRole = "\"" + role.replace("\"", "\"\"") + "\"";
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER ROLE " + quotedRole + " NOLOGIN");
                statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        + "WHERE datname=current_database() AND usename=current_user AND pid<>pg_backend_pid()");

                try {
                    send(eventId, orderId, json);
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.sql.SQLException("Interrupted while PostgreSQL logins were disabled", interrupted);
                } catch (Exception failure) {
                    throw new java.sql.SQLException("Could not publish outage acceptance event", failure);
                }
                try (var check = connection.prepareStatement("SELECT COUNT(*) FROM event_inbox "
                        + "WHERE consumer_name='order-notification-v1' AND event_id=?")) {
                    check.setObject(1, eventId);
                    try (var result = check.executeQuery()) {
                        result.next();
                        assertThat(result.getInt(1))
                                .as("the consumer cannot commit while PostgreSQL rejects new connections")
                                .isZero();
                    }
                }
            } finally {
                try (var restore = connection.createStatement()) {
                    restore.execute("ALTER ROLE " + quotedRole + " LOGIN");
                }
            }
            return null;
        });

        awaitInbox(eventId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kafka_consumer_dead_letters "
                        + "WHERE source_topic=? AND event_id=?", Integer.class, TOPIC, eventId.toString()))
                .isZero();
    }

    @Test
    void rejoinsNotificationConsumerGroupAndProcessesEventsAfterPartitionRebalance() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "rebalance-recovery-" + userId + "@example.test", "unused-test-hash", "Group", "Recovery");

        var listener = listenerRegistry.getListenerContainers().stream().findFirst()
                .orElseThrow(() -> new AssertionError("Order event listener container was not registered"));
        listener.stop();
        assertThat(listener.isRunning()).isFalse();

        String json = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                "type", "ORDER_CREATED", "occurredAt", Instant.now(), "orderId", orderId,
                "userId", userId, "locale", "en"));
        send(eventId, orderId, json);

        // Stopping and rejoining the sole member forces Kafka to revoke and reassign its partition.
        listener.start();
        awaitInbox(eventId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + orderId)).isEqualTo(1);
        var lag = new KafkaConsumerLagController(kafkaProperties, TOPIC, "artio-notification-worker").consumerLag();
        assertThat(lag.totalLag()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM kafka_consumer_dead_letters "
                        + "WHERE source_topic=? AND event_id=?", Integer.class, TOPIC, eventId.toString()))
                .isZero();
    }

    @Test
    void recoversNotificationDeliveryClaimAbandonedBeforeWorkerRestart() {
        jdbc.update("UPDATE notification_delivery_outbox SET status='SENT',locked_at=NULL WHERE status='PENDING'");
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "recovered-worker-" + userId + "@example.test", "unused-test-hash", "Recovery", "Worker");
        jdbc.update("INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body,status,attempts,locked_at) "
                        + "VALUES (?,?,?,?,?,?,'PROCESSING',1,now()-interval '6 minutes')",
                deliveryId, "recovery-test:" + deliveryId, userId, orderId, "Recovery acceptance", "retry after abandoned claim");

        var calls = new AtomicInteger();
        var transport = new io.artio.extensions.commerce.NotificationTransport() {
            @Override public DeliveryResult deliver(OutboundMessage message) {
                calls.incrementAndGet();
                return new DeliveryResult(true, "recovery-acceptance", null, null);
            }
        };
        var beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("recoveryAcceptanceTransport", transport);
        var handler = new OrderNotificationHandler(jdbc,
                beanFactory.getBeanProvider(io.artio.extensions.commerce.NotificationTransport.class), true);

        handler.deliverPendingBatch();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_delivery_outbox WHERE id=?", Integer.class, deliveryId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT locked_at IS NULL FROM notification_delivery_outbox WHERE id=?", Boolean.class, deliveryId)).isTrue();
    }

    @Test
    void concurrentNotificationReplicasClaimAndDeliverEachOutboxMessageOnlyOnce() throws Exception {
        jdbc.update("UPDATE notification_delivery_outbox SET status='SENT',locked_at=NULL WHERE status='PENDING'");
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "replica-worker-" + userId + "@example.test", "unused-test-hash", "Replica", "Worker");
        jdbc.update("INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body) VALUES (?,?,?,?,?,?)",
                deliveryId, "replica-test:" + deliveryId, userId, orderId, "Replica acceptance", "single delivery");

        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var transport = new io.artio.extensions.commerce.NotificationTransport() {
            @Override public DeliveryResult deliver(OutboundMessage message) {
                calls.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("replica delivery timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("replica delivery interrupted", interrupted);
                }
                return new DeliveryResult(true, "replica-acceptance", null, null);
            }
        };
        var beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("replicaAcceptanceTransport", transport);
        var provider = beanFactory.getBeanProvider(io.artio.extensions.commerce.NotificationTransport.class);
        var firstReplica = new OrderNotificationHandler(jdbc, provider, true);
        var secondReplica = new OrderNotificationHandler(jdbc, provider, true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(firstReplica::deliverPendingBatch);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(secondReplica::deliverPendingBatch);
            second.get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_delivery_outbox WHERE id=?", Integer.class, deliveryId)).isEqualTo(1);
    }

    @Test
    void kafkaLagDiagnosticsReadCommittedAndEndOffsetsFromTheBroker() {
        var diagnostics = new KafkaConsumerLagController(kafkaProperties, TOPIC, "artio-notification-worker").consumerLag();
        assertThat(diagnostics.topic()).isEqualTo(TOPIC);
        assertThat(diagnostics.group()).isEqualTo("artio-notification-worker");
        assertThat(diagnostics.partitions()).hasSize(1);
        var partition = diagnostics.partitions().getFirst();
        assertThat(partition.partition()).isZero();
        assertThat(partition.committedOffset()).isGreaterThanOrEqualTo(0);
        assertThat(partition.endOffset()).isGreaterThanOrEqualTo(partition.committedOffset());
        assertThat(partition.lag()).isEqualTo(partition.endOffset() - partition.committedOffset());
        assertThat(diagnostics.totalLag()).isEqualTo(partition.lag());
    }

    private void send(UUID eventId, UUID orderId, String json) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, orderId.toString(), json);
        record.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event-type", "ORDER_CREATED".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("schema-version", "1".getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    private PluginManifest packageManifest(String pluginId, String image) {
        return new PluginManifest("1", pluginId, "Event subscriber", "Artio test fixtures", "2.0.0",
                "Isolated order event subscriber fixture", "1.0.0", "99.0.0", image, java.util.List.of(),
                java.util.List.of(), Map.of(), java.util.List.of("events.subscriber.v1"), "/health", "/diagnostics");
    }

    private String v2Manifest(PluginManifest packageManifest) throws Exception {
        return mapper.writeValueAsString(Map.of("packageManifest", packageManifest,
                "subscriptions", java.util.List.of(Map.of("eventType", "order.created", "schemaVersion", 1,
                        "permissions", java.util.List.of())), "capabilities", java.util.List.of()));
    }

    private void awaitInbox(UUID eventId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE consumer_name='order-notification-v1' AND event_id=?",
                    Integer.class, eventId);
            if (count != null && count == 1) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka consumer did not persist event inbox receipt within 15 seconds");
    }
}
