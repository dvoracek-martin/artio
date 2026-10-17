package io.artio.api.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "artio.cache.redis", name = "enabled", havingValue = "true")
class OrderUpdateFanoutConfiguration {
    @Bean
    RedisMessageListenerContainer orderUpdateFanoutListenerContainer(RedisConnectionFactory connectionFactory,
            RedisOrderUpdateFanout fanout) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(fanout, new ChannelTopic(RedisOrderUpdateFanout.CHANNEL));
        return container;
    }
}
