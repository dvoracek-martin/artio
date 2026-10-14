package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises broker delivery, transactional inbox deduplication and durable notification enqueue. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
