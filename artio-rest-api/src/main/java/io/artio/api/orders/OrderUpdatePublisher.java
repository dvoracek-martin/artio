package io.artio.api.orders;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderUpdatePublisher {
    private final JdbcTemplate jdbc;private final SimpMessagingTemplate messaging;
    public OrderUpdatePublisher(JdbcTemplate jdbc,ObjectProvider<SimpMessagingTemplate> messaging){this.jdbc=jdbc;this.messaging=messaging.getIfAvailable();}
    public void publish(UUID orderId){if(messaging==null)return;var rows=jdbc.query("SELECT user_id,status,total,currency,tracking_number FROM orders WHERE id=?",(rs,n)->new Row(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),rs.getString(4).trim(),rs.getString(5)),orderId);if(rows.isEmpty())return;Row row=rows.getFirst();messaging.convertAndSendToUser(row.userId().toString(),"/queue/orders/"+orderId,new OrderStreamController.OrderUpdate(orderId,row.status(),row.total(),row.currency(),row.tracking(),Instant.now()));}
    private record Row(UUID userId,String status,java.math.BigDecimal total,String currency,String tracking){}
}
