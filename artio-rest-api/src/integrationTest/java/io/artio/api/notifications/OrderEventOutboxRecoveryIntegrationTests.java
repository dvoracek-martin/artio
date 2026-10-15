package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Verifies that a publisher restart recovers an abandoned PostgreSQL claim and reaches Kafka. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class OrderEventOutboxRecoveryIntegrationTests {
    private static final String TOPIC = "artio.order.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired OrderEventOutboxPublisher publisher;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> "integration-test-encryption-key-value-32-bytes-minimum");
        registry.add("artio.setup.token", () -> "integration-test-setup-token-value-32-bytes-minimum");
        registry.add("artio.events.kafka.enabled", () -> "true");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.events.kafka.order-topic", () -> TOPIC);
        registry.add("artio.events.outbox-poll-interval-ms", () -> "3600000");
        registry.add("artio.workers.enabled", () -> "false");
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (var admin = AdminClient.create(java.util.Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void republishesOrderEventOnceAfterPublisherRestartWithAbandonedClaim() throws Exception {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        String payload = "{\"schemaVersion\":1,\"eventId\":\"" + eventId + "\"}";
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload,status,attempts,locked_at) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,?::jsonb,'PROCESSING',1,now()-interval '6 minutes')",
                eventId, aggregateId, payload);

        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "recovery-verifier-" + UUID.randomUUID());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            var partition = new TopicPartition(TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.poll(Duration.ofMillis(100));

            publisher.publishPending();

            assertThat(jdbc.queryForObject("SELECT status FROM event_outbox WHERE id=?", String.class, eventId)).isEqualTo("PUBLISHED");
            assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE id=?", Integer.class, eventId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT locked_at IS NULL FROM event_outbox WHERE id=?", Boolean.class, eventId)).isTrue();

            consumer.seekToBeginning(List.of(partition));
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> recovered = null;
            while (System.nanoTime() < deadline && recovered == null) {
                var records = consumer.poll(Duration.ofMillis(250));
                recovered = records.records(partition).stream().filter(record -> aggregateId.equals(record.key())).findFirst().orElse(null);
            }
            assertThat(recovered).as("the recovered event should be visible on Kafka").isNotNull();
            assertThat(recovered.value()).contains(eventId.toString());
        }
    }
}
