package io.artio.api.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only broker-backed consumer lag diagnostics. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/admin/operations/kafka/consumer-lag")
public class KafkaConsumerLagController {
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private final KafkaProperties kafkaProperties;
    private final String topic;
    private final String group;

    public KafkaConsumerLagController(KafkaProperties kafkaProperties,
            @Value("${artio.events.kafka.order-topic:artio.order.events}") String topic,
            @Value("${artio.events.kafka.notification-consumer-group:artio-notification-worker}") String group) {
        this.kafkaProperties = kafkaProperties;
        this.topic = topic;
        this.group = group;
    }

    @GetMapping
    public ConsumerLag consumerLag() {
        Properties properties = new Properties();
        properties.putAll(kafkaProperties.buildAdminProperties(null));
        properties.putIfAbsent("request.timeout.ms", 3_000);
        properties.putIfAbsent("default.api.timeout.ms", 5_000);
        try (AdminClient admin = AdminClient.create(properties)) {
            Map<TopicPartition, Long> committed = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            entry -> entry.getValue() == null ? 0L : Math.max(0L, entry.getValue().offset())));
            Map<TopicPartition, OffsetSpec> requests = new TreeMap<>(java.util.Comparator
                    .comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
            admin.describeTopics(java.util.List.of(topic)).allTopicNames().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .get(topic).partitions().forEach(partition -> {
                        TopicPartition key = new TopicPartition(topic, partition.partition());
                        requests.put(key, OffsetSpec.latest());
                    });
            Map<TopicPartition, ListOffsetsResultInfo> latest = admin.listOffsets(requests)
                    .all().get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var partitions = latest.entrySet().stream().map(entry -> {
                long end = Math.max(0L, entry.getValue().offset());
                long position = committed.getOrDefault(entry.getKey(), 0L);
                long lag = Math.max(0L, end - position);
                return new PartitionLag(entry.getKey().partition(), position, end, lag);
            }).sorted(java.util.Comparator.comparingInt(PartitionLag::partition)).toList();
            return new ConsumerLag(topic, group, partitions.stream().mapToLong(PartitionLag::lag).sum(), partitions);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kafka consumer lag is temporarily unavailable", failure);
        }
    }

    public record ConsumerLag(String topic, String group, long totalLag, java.util.List<PartitionLag> partitions) { }
    public record PartitionLag(int partition, long committedOffset, long endOffset, long lag) { }
}
