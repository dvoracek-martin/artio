package io.artio.api.orders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RedisOrderUpdateFanoutTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publishesOnlyThePrivateOrderUpdateEnvelope() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var fanout = new RedisOrderUpdateFanout(redis, mapper, messaging);
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var update = new OrderStreamController.OrderUpdate(orderId, "PAID", new BigDecimal("12.50"), "CHF", null,
                Instant.parse("2026-10-17T12:00:00Z"));

        fanout.publish(userId, update);

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq(RedisOrderUpdateFanout.CHANNEL), payload.capture());
        var json = mapper.readTree(payload.getValue());
        org.assertj.core.api.Assertions.assertThat(json.path("userId").asText()).isEqualTo(userId.toString());
        org.assertj.core.api.Assertions.assertThat(json.path("update").path("orderId").asText()).isEqualTo(orderId.toString());
        org.assertj.core.api.Assertions.assertThat(json.toString()).doesNotContain("email", "password", "token");
    }

    @Test
    void forwardsRedisEventsOnlyToTheUserDestinationForTheOwnedOrder() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var fanout = new RedisOrderUpdateFanout(redis, mapper, messaging);
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new RedisOrderUpdateFanout.OrderUpdateEvent(userId,
                new OrderStreamController.OrderUpdate(orderId, "SHIPPED", new BigDecimal("12.50"), "CHF", "TRACK-1",
                        Instant.parse("2026-10-17T12:00:00Z"))));
        org.assertj.core.api.Assertions.assertThat(mapper.readValue(payload, RedisOrderUpdateFanout.OrderUpdateEvent.class)
                .userId()).isEqualTo(userId);

        var message = mock(Message.class);
        when(message.getBody()).thenReturn(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(payload);
        org.assertj.core.api.Assertions.assertThat(fanout.decode(message.getBody()).userId()).isEqualTo(userId);
        fanout.onMessage(message, null);

        verify(messaging).convertAndSendToUser(userId.toString(), "/queue/orders/" + orderId,
                new OrderStreamController.OrderUpdate(orderId, "SHIPPED", new BigDecimal("12.50"), "CHF", "TRACK-1",
                        Instant.parse("2026-10-17T12:00:00Z")));
    }

    @Test
    void ignoresMalformedRedisEventsWithoutThrowing() {
        var redis = mock(StringRedisTemplate.class);
        var messaging = mock(SimpMessagingTemplate.class);
        var fanout = new RedisOrderUpdateFanout(redis, mapper, messaging);

        var message = mock(Message.class);
        when(message.getBody()).thenReturn("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fanout.onMessage(message, null);

        org.mockito.Mockito.verifyNoInteractions(messaging);
    }
}
