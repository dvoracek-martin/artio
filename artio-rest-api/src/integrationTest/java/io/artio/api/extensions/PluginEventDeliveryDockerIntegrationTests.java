package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import io.artio.api.security.EncryptionService;
import io.artio.api.security.ExtensionCredentialService;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Live PostgreSQL + Kafka + two isolated Docker instances for frozen subscriber fan-out and recovery. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PluginEventDeliveryDockerIntegrationTests {
    private static final String TOPIC = "artio.order.events";
    private static final String DLQ = TOPIC + ".dlq";
    private static final String KEY_MATERIAL = "integration-test-key-material-that-is-at-least-32-bytes";

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired PluginEventDeliveryOperations eventDeliveryOperations;

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
        registry.add("artio.events.kafka.consumer-concurrency", () -> "1");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.workers.enabled", () -> "false");
    }

    @BeforeAll
    static void createTopics() throws Exception {
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1), new NewTopic(DLQ, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void deliversFrozenFanoutToTwoRealInstancesAndRecoversExpiredLeaseAfterRestart() throws Exception {
        String image = System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V1");
        assumeTrue(image != null && image.matches(".+@sha256:[a-f0-9]{64}"),
                "Run scripts/verify-extension-runtime.ps1 to build the pinned reference subscriber image");
        String runId = System.getenv().getOrDefault("ARTIO_TEST_RUN_ID", UUID.randomUUID().toString().replace("-", ""));
        String network = "artio-event-it-" + runId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String pluginId = "runtime.e2e" + runId + ".events";
        String firstIdentity = pluginId;
        String secondIdentity = "instance-" + UUID.randomUUID().toString().replace("-", "");
        UUID firstInstance = UUID.randomUUID();
        UUID secondInstance = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID firstSubscription = UUID.randomUUID();
        UUID secondSubscription = UUID.randomUUID();
        String firstSecret = encryption.encrypt("first-subscriber-secret");
        String secondSecret = encryption.encrypt("second-subscriber-secret");

        PluginManifest manifest = new PluginManifest("2", pluginId, "Event Acceptance", "Artio", "2.0.0",
                "Durable isolated subscriber fixture", "0.1.0", "9.0.0", image, List.of(), List.of(), Map.of(),
                List.of("events.subscriber.v1"), "/health", "/diagnostics", PluginManifest.GeographicAvailability.global(),
                List.of(), List.of("/var/lib/artio-reference"), Map.of());
        insertSubscribers(manifest, firstIdentity, secondIdentity, firstInstance, secondInstance,
                firstSubscription, secondSubscription, firstSecret, secondSecret);
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,?,?,?,?,now())",
                userId, "docker-event-" + userId + "@example.test", "unused-test-hash", "Event", "Docker");

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var transport = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10).connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)).build();
        try (DockerClient docker = DockerClientImpl.getInstance(config, transport)) {
            docker.createNetworkCmd().withName(network).withDriver("bridge").withLabels(Map.of("io.artio.test.run", runId)).exec();
            var credentials = new ExtensionCredentialService(KEY_MATERIAL);
            var firstRuntime = new DockerExtensionRuntime(docker, network, 8080, credentials, true,
                    "http://extension-egress:3128", runId);
            var secondRuntime = new DockerExtensionRuntime(docker, network, 8080, credentials, true,
                    "http://extension-egress:3128", runId);
            try {
                firstRuntime.installInstance(firstIdentity, manifest);
                secondRuntime.installInstance(secondIdentity, manifest);
                try {
                    firstRuntime.enableInstance(firstIdentity, manifest, 1, Map.of());
                } catch (RuntimeException failure) {
                    String name = DockerExtensionRuntime.containerName(firstIdentity);
                    var state = docker.inspectContainerCmd(name).exec().getState();
                    var health = firstRuntime.healthInstance(firstIdentity, manifest);
                    StringBuilder logs = new StringBuilder();
                    var callback = new com.github.dockerjava.core.command.LogContainerResultCallback() {
                        @Override public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            logs.append(new String(frame.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
                            super.onNext(frame);
                        }
                    };
                    try {
                        docker.logContainerCmd(name).withFollowStream(false).withStdOut(true).withStdErr(true)
                                .exec(callback).awaitCompletion(5, TimeUnit.SECONDS);
                    } catch (Exception logFailure) { logs.append("<unavailable: ").append(logFailure.getClass().getSimpleName()).append(">"); }
                    throw new IllegalStateException("Event subscriber failed readiness; state=" + state.getStatus()
                            + ", error=" + state.getError() + ", health=" + health.services() + ", logs: " + logs, failure);
                }
                secondRuntime.enableInstance(secondIdentity, manifest, 1, Map.of());

                String payload = mapper.writeValueAsString(Map.of("schemaVersion", 1, "eventId", eventId,
                        "type", "ORDER_CREATED", "occurredAt", Instant.now(), "orderId", orderId,
                        "userId", userId, "locale", "en"));
                var record = new ProducerRecord<String, String>(TOPIC, orderId.toString(), payload);
                record.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("event-type", "ORDER_CREATED".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("schema-version", "1".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var listener = listenerRegistry.getListenerContainers().stream().findFirst()
                        .orElseThrow(() -> new AssertionError("Order event dispatcher listener was not registered"));
                listener.stop();
                // Make a real group reassignment: a temporary Core consumer first owns all partitions,
                // then the application listener joins and splits the assignments, and finally takes
                // the partitions back when the temporary member leaves.
                var consumerProperties = new Properties();
                consumerProperties.put("bootstrap.servers", KAFKA.getBootstrapServers());
                consumerProperties.put("group.id", "artio-notification-worker");
                consumerProperties.put("key.deserializer", StringDeserializer.class.getName());
                consumerProperties.put("value.deserializer", StringDeserializer.class.getName());
                consumerProperties.put("auto.offset.reset", "earliest");
                consumerProperties.put("enable.auto.commit", "false");
                try (KafkaConsumer<String, String> rebalanceMember = new KafkaConsumer<>(consumerProperties)) {
                    rebalanceMember.subscribe(List.of(TOPIC));
                    awaitAssignment(rebalanceMember, 3);
                    listener.start();
                    Instant rebalanceDeadline = Instant.now().plusSeconds(20);
                    while ((rebalanceMember.assignment().size() < 1 || rebalanceMember.assignment().size() > 2)
                            && Instant.now().isBefore(rebalanceDeadline)) {
                        rebalanceMember.poll(Duration.ofMillis(100));
                    }
                    assertThat(rebalanceMember.assignment()).hasSizeBetween(1, 2);
                    rebalanceMember.poll(Duration.ofMillis(100));
                    listener.stop();
                    rebalanceMember.poll(Duration.ofMillis(100));
                }
                // The application listener rejoins as the sole member; publish after its reassignment.
                listener.start();
                kafka.send(record).get(10, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_inbox WHERE consumer_name='order-notification-v1' AND event_id=?",
                        Integer.class, eventId)).isZero();
                awaitDeliveries(eventId);
                Map<String, UUID> deliveryIds = jdbc.query("SELECT delivery_id,runtime_identity FROM extension_event_deliveries WHERE inbox_event_id=?",
                        rs -> {
                            var ids = new java.util.HashMap<String, UUID>();
                            while (rs.next()) ids.put(rs.getString("runtime_identity"), rs.getObject("delivery_id", UUID.class));
                            return Map.copyOf(ids);
                        }, eventId);
                UUID firstDelivery = deliveryIds.get(firstIdentity);
                UUID secondDelivery = deliveryIds.get(secondIdentity);
                assertThat(deliveryIds).containsOnlyKeys(firstIdentity, secondIdentity);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=? AND state='PENDING'",
                        Integer.class, eventId)).isEqualTo(2);

                // A subscription enabled after this event's inbox transaction cannot join its frozen fan-out.
                UUID lateInstance = UUID.randomUUID();
                UUID lateSubscription = UUID.randomUUID();
                String lateIdentity = "late-instance-" + UUID.randomUUID().toString().replace("-", "");
                jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                                + "lifecycle_state,runtime_identity,configuration_generation,public_configuration,encrypted_secrets) "
                                + "VALUES (?,?,'late',?,'CAPABILITY_V2','ENABLED',?,1,'{\"region\":\"eu\"}'::jsonb,?::jsonb)",
                        lateInstance, pluginId, jdbc.queryForObject("SELECT release_id FROM extension_plugin_releases WHERE plugin_id=?",
                                Long.class, pluginId), lateIdentity, "{\"apiKey\":\"" + firstSecret + "\"}");
                jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version) "
                        + "VALUES (?,?,'order.created',1)", lateSubscription, lateInstance);
                kafka.send(record).get(10, TimeUnit.SECONDS);
                awaitConsumerLagZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=?",
                        Integer.class, eventId)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE subscription_id=?",
                        Integer.class, lateSubscription)).isZero();

                ExtensionRuntimePort router = new ExtensionRuntimePort() {
                    @Override public void install(PluginManifest release) { throw new UnsupportedOperationException(); }
                    @Override public void enable(PluginManifest release) { throw new UnsupportedOperationException(); }
                    @Override public void disable(String id) { throw new UnsupportedOperationException(); }
                    @Override public RuntimeHealth health(String id, String path) { throw new UnsupportedOperationException(); }
                    @Override public InvocationResult invoke(String id, String point, String operation, String key,
                            String configuration, String request, Duration deadline) {
                        return delegate(id).invoke(id, point, operation, key, configuration, request, deadline);
                    }
                    @Override public InvocationResult invoke(String id, int generation, String point, String operation, String key,
                            String configuration, String request, Duration deadline) {
                        return delegate(id).invoke(id, generation, point, operation, key, configuration, request, deadline);
                    }
                    private DockerExtensionRuntime delegate(String id) {
                        return firstIdentity.equals(id) ? firstRuntime : secondRuntime;
                    }
                };
                var factory = new StaticListableBeanFactory(Map.of("runtime", router));
                var worker = new PluginEventDeliveryWorker(jdbc, mapper, encryption,
                        factory.getBeanProvider(ExtensionRuntimePort.class));
                worker.deliverPending();
                Integer deliveredCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=? AND state='DELIVERED'",
                        Integer.class, eventId);
                assertThat(deliveredCount).withFailMessage("Expected both Docker subscribers to acknowledge delivery; rows: %s",
                        jdbc.queryForList("SELECT runtime_identity,state,last_error_code,attempt_count FROM extension_event_deliveries "
                                + "WHERE inbox_event_id=? ORDER BY runtime_identity", eventId)).isEqualTo(2);

                String frozenPayload = jdbc.queryForObject("SELECT payload_snapshot::text FROM extension_event_deliveries WHERE delivery_id=?",
                        String.class, firstDelivery);
                UUID staleLease = UUID.randomUUID();
                jdbc.update("UPDATE extension_event_deliveries SET state='IN_PROGRESS',lease_token=?,lease_until=now()-interval '1 second' "
                        + "WHERE delivery_id=?", staleLease, firstDelivery);
                worker.deliverPending();
                assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE delivery_id=?", String.class,
                        firstDelivery)).isEqualTo("DELIVERED");

                firstRuntime.disableInstance(firstIdentity, pluginId);
                firstRuntime.enableInstance(firstIdentity, manifest, 1, Map.of());
                var repeated = firstRuntime.invoke(firstIdentity, 1, "events.subscriber.v1", "deliver",
                        firstDelivery.toString(), "{}", frozenPayload, Duration.ofSeconds(5));
                var acknowledgement = mapper.readTree(repeated.responseJson());
                assertThat(acknowledgement.path("accepted").asBoolean()).isTrue();
                assertThat(acknowledgement.path("deliveryId").asText()).isEqualTo(firstDelivery.toString());
                assertThat(acknowledgement.path("duplicate").asBoolean()).isTrue();

                // Exercise the plugin delivery DLQ path and the audited operator replay action using
                // the same immutable delivery identity after the container becomes available again.
                firstRuntime.disableInstance(firstIdentity, pluginId);
                jdbc.update("UPDATE extension_event_deliveries SET state='RETRY',attempt_count=11,available_at=now() "
                        + "WHERE delivery_id=?", firstDelivery);
                worker.deliverPending();
                assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE delivery_id=?",
                        String.class, firstDelivery)).isEqualTo("DEAD_LETTER");
                assertThat(eventDeliveryOperations.replay(firstDelivery, userId))
                        .isEqualTo(PluginEventDeliveryOperations.ReplayResult.ACCEPTED);
                firstRuntime.enableInstance(firstIdentity, manifest, 1, Map.of());
                worker.deliverPending();
                assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE delivery_id=?",
                        String.class, firstDelivery)).isEqualTo("DELIVERED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_audit_events WHERE plugin_id=? "
                                + "AND action='REPLAY_EVENT_DELIVERY' AND details->>'deliveryId'=?", Integer.class,
                        pluginId, firstDelivery.toString())).isEqualTo(1);
            } finally {
                firstRuntime.disableInstance(firstIdentity, pluginId);
                firstRuntime.uninstallInstance(firstIdentity, pluginId);
                secondRuntime.disableInstance(secondIdentity, pluginId);
                secondRuntime.uninstallInstance(secondIdentity, pluginId);
                docker.removeNetworkCmd(network).exec();
            }
        }
    }

    private void insertSubscribers(PluginManifest manifest, String firstIdentity, String secondIdentity,
            UUID firstInstance, UUID secondInstance, UUID firstSubscription, UUID secondSubscription,
            String firstSecret, String secondSecret) throws Exception {
        String pluginId = manifest.id();
        String image = manifest.image();
        String releaseManifest = mapper.writeValueAsString(manifest);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state,public_configuration) "
                        + "VALUES (?,?::jsonb,?,'ENABLED',?::jsonb)", pluginId, releaseManifest, image, "{\"region\":\"eu\"}");
        long releaseId = jdbc.queryForObject("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) "
                        + "VALUES (?, '2.0.0', ?, ?::jsonb) RETURNING release_id", Long.class, pluginId, image, releaseManifest);
        var v2Manifest = new io.artio.extensions.PluginManifestV2(manifest, List.of(),
                List.of(new io.artio.extensions.PluginManifestV2.EventSubscription("order.created", 1, List.of())));
        jdbc.update("INSERT INTO extension_plugin_v2_releases(release_id,plugin_id,manifest) VALUES (?, ?, ?::jsonb)",
                releaseId, pluginId, mapper.writeValueAsString(v2Manifest));
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,configuration_generation,public_configuration,encrypted_secrets) "
                        + "VALUES (?,?,'primary',?,'CAPABILITY_V2','ENABLED',?,1,'{\"region\":\"eu\"}'::jsonb,?::jsonb)",
                firstInstance, pluginId, releaseId, firstIdentity, "{\"apiKey\":\"" + firstSecret + "\"}");
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity,configuration_generation,public_configuration,encrypted_secrets) "
                        + "VALUES (?,?,'replica',?,'CAPABILITY_V2','ENABLED',?,1,'{\"region\":\"na\"}'::jsonb,?::jsonb)",
                secondInstance, pluginId, releaseId, secondIdentity, "{\"apiKey\":\"" + secondSecret + "\"}");
        jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version) VALUES (?,?,'order.created',1)",
                firstSubscription, firstInstance);
        jdbc.update("INSERT INTO extension_event_subscriptions(subscription_id,instance_id,event_type,schema_version) VALUES (?,?,'order.created',1)",
                secondSubscription, secondInstance);
    }

    private void awaitDeliveries(UUID eventId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=?",
                    Integer.class, eventId);
            if (count != null && count == 2) return;
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM extension_event_deliveries WHERE inbox_event_id=?",
                Integer.class, eventId)).isEqualTo(2);
    }

    private static void awaitAssignment(KafkaConsumer<String, String> consumer, int expectedPartitions)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (consumer.assignment().size() != expectedPartitions && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).hasSize(expectedPartitions);
    }

    private void awaitConsumerLagZero() throws InterruptedException {
        var lagController = new io.artio.api.notifications.KafkaConsumerLagController(
                kafkaProperties, TOPIC, "artio-notification-worker");
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (lagController.consumerLag().totalLag() == 0) return;
            } catch (RuntimeException unavailable) {
                // The admin client may observe the rejoin before it observes the committed offset.
            }
            Thread.sleep(100);
        }
        assertThat(lagController.consumerLag().totalLag()).isZero();
    }
}
