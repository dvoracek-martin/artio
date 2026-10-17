package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class OrderUpdateRedisFanoutIntegrationTests {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(
            "redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"))
            .withExposedPorts(6379);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publishesCommittedOrderUpdateToEveryApiReplicaWebSocketBroker() throws Exception {
        LettuceConnectionFactory firstConnection = connectionFactory();
        LettuceConnectionFactory secondConnection = connectionFactory();
        LettuceConnectionFactory publisherConnection = connectionFactory();
        RedisMessageListenerContainer firstListener = null;
        RedisMessageListenerContainer secondListener = null;
        try {
            SimpMessagingTemplate firstMessaging = mock(SimpMessagingTemplate.class);
            SimpMessagingTemplate secondMessaging = mock(SimpMessagingTemplate.class);
            var firstDelivered = new AtomicBoolean();
            var secondDelivered = new AtomicBoolean();
            var bothDelivered = new CountDownLatch(2);
            doAnswer(invocation -> {
                if (firstDelivered.compareAndSet(false, true)) bothDelivered.countDown();
                return null;
            }).when(firstMessaging).convertAndSendToUser(anyString(), anyString(), any());
            doAnswer(invocation -> {
                if (secondDelivered.compareAndSet(false, true)) bothDelivered.countDown();
                return null;
            }).when(secondMessaging).convertAndSendToUser(anyString(), anyString(), any());

            var firstFanout = new RedisOrderUpdateFanout(template(firstConnection), mapper, firstMessaging);
            var secondFanout = new RedisOrderUpdateFanout(template(secondConnection), mapper, secondMessaging);
            firstListener = listener(firstConnection, firstFanout);
            secondListener = listener(secondConnection, secondFanout);
            var publisher = new RedisOrderUpdateFanout(template(publisherConnection), mapper,
                    mock(SimpMessagingTemplate.class));
            UUID orderId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            var update = new OrderStreamController.OrderUpdate(orderId, "PAID", new BigDecimal("25.00"), "CHF", null,
                    Instant.parse("2026-10-17T12:00:00Z"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (bothDelivered.getCount() > 0 && System.nanoTime() < deadline) {
                publisher.publish(userId, update);
                bothDelivered.await(50, TimeUnit.MILLISECONDS);
            }

            assertThat(firstDelivered.get()).isTrue();
            assertThat(secondDelivered.get()).isTrue();
        } finally {
            stop(firstListener);
            stop(secondListener);
            firstConnection.destroy();
            secondConnection.destroy();
            publisherConnection.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory() {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        return factory;
    }

    private StringRedisTemplate template(LettuceConnectionFactory factory) {
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private RedisMessageListenerContainer listener(LettuceConnectionFactory factory, RedisOrderUpdateFanout fanout)
            throws Exception {
        var listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(factory);
        listener.addMessageListener(fanout, new ChannelTopic(RedisOrderUpdateFanout.CHANNEL));
        listener.afterPropertiesSet();
        listener.start();
        return listener;
    }

    private void stop(RedisMessageListenerContainer listener) throws Exception {
        if (listener == null) return;
        listener.stop();
        listener.destroy();
    }
}
