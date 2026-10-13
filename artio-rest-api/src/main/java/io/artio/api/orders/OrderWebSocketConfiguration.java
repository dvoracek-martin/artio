package io.artio.api.orders;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class OrderWebSocketConfiguration implements WebSocketMessageBrokerConfigurer {
    private final JwtDecoder decoder;
    private final JdbcTemplate jdbc;
    private final String[] allowedOrigins;
    public OrderWebSocketConfiguration(JwtDecoder decoder,JdbcTemplate jdbc,@Value("${artio.security.allowed-origins:http://localhost:4200,http://localhost:4201}") String origins){this.decoder=decoder;this.jdbc=jdbc;this.allowedOrigins=origins.split(",");}
    @Override public void registerStompEndpoints(StompEndpointRegistry registry){registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);}
    @Override public void configureMessageBroker(MessageBrokerRegistry registry){registry.setApplicationDestinationPrefixes("/app");registry.enableSimpleBroker("/queue");registry.setUserDestinationPrefix("/user");}
    @Override public void configureClientInboundChannel(ChannelRegistration registration){
        registration.interceptors(new ChannelInterceptor(){
            @Override public Message<?> preSend(Message<?> message,MessageChannel channel){
                StompHeaderAccessor accessor=MessageHeaderAccessor.getAccessor(message,StompHeaderAccessor.class);
                if(accessor==null)return message;
                if(StompCommand.CONNECT.equals(accessor.getCommand())){
                    String header=accessor.getFirstNativeHeader("Authorization");
                    if(header==null||!header.startsWith("Bearer "))throw new IllegalArgumentException("Bearer token required for WebSocket connection");
                    Jwt jwt=decoder.decode(header.substring(7));
                    String role=jwt.getClaimAsString("role");
                    var authentication=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_"+role)));
                    accessor.setUser(authentication);
                }
                if(StompCommand.SUBSCRIBE.equals(accessor.getCommand())){
                    if(!(accessor.getUser() instanceof JwtAuthenticationToken auth))throw new IllegalArgumentException("Authenticated WebSocket session required");
                    String destination=accessor.getDestination();
                    if(destination==null||!destination.matches("/user/queue/orders/[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("Only private order subscriptions are supported");
                    UUID orderId=UUID.fromString(destination.substring(destination.lastIndexOf('/')+1));
                    UUID user=UUID.fromString(auth.getToken().getSubject());
                    UUID tenant=UUID.fromString(auth.getToken().getClaimAsString("tenant_id"));
                    Boolean allowed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM orders o JOIN users u ON u.id=o.user_id JOIN tenant_memberships m ON m.user_id=u.id JOIN tenants t ON t.id=m.tenant_id WHERE o.id=? AND o.user_id=? AND m.tenant_id=? AND t.active)",Boolean.class,orderId,user,tenant);
                    if(!Boolean.TRUE.equals(allowed))throw new IllegalArgumentException("Order subscription is not authorized");
                }
                return message;
            }
        });
    }
}
