package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import io.artio.extensions.commerce.NotificationTransport.DeliveryResult;
import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaProperties kafkaProperties;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

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
