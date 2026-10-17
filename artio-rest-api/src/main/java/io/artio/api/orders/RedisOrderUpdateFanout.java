package io.artio.api.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Redis pub/sub bridge that forwards committed order updates to WebSocket sessions on every API replica. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "artio.cache.redis", name = "enabled", havingValue = "true")
public class RedisOrderUpdateFanout implements MessageListener {
    static final String CHANNEL = "artio:realtime:order-updates:v1";
    private static final Logger log = LoggerFactory.getLogger(RedisOrderUpdateFanout.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SimpMessagingTemplate messaging;

    RedisOrderUpdateFanout(StringRedisTemplate redis, ObjectMapper mapper, SimpMessagingTemplate messaging) {
        this.redis = redis;
        this.mapper = mapper;
        this.messaging = messaging;
    }

    void publish(UUID userId, OrderStreamController.OrderUpdate update) {
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(new OrderUpdateEvent(userId, update)));
        } catch (JsonProcessingException invalidUpdate) {
            throw new IllegalStateException("Unable to encode order update", invalidUpdate);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            OrderUpdateEvent event = decode(message.getBody());
            String destination = "/queue/orders/" + event.update().orderId();
            messaging.convertAndSendToUser(event.userId().toString(), destination, event.update());
        } catch (Exception invalidMessage) {
            log.warn("Ignoring malformed realtime order update from Redis pub/sub ({})",
                    invalidMessage.getClass().getSimpleName());
        }
    }

    OrderUpdateEvent decode(byte[] body) throws IOException {
        return mapper.readValue(body, OrderUpdateEvent.class);
    }

    record OrderUpdateEvent(UUID userId, OrderStreamController.OrderUpdate update) { }
}
