package io.artio.api.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.orders.OrderCreated;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderNotificationHandler {
    private static final Logger log=LoggerFactory.getLogger(OrderNotificationHandler.class);
    private final JdbcTemplate jdbc; private final KafkaTemplate<String,String> kafka; private final ObjectMapper mapper;
    private final ObjectProvider<JavaMailSender> mailSender; private final String from;
    public OrderNotificationHandler(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,ObjectMapper mapper,
            ObjectProvider<JavaMailSender> mailSender,@Value("${artio.mail-from}") String from){this.jdbc=jdbc;this.kafka=kafka;this.mapper=mapper;this.mailSender=mailSender;this.from=from;}

    @Async
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreated event) {
        String orderId=event.orderId().value().toString();
        jdbc.update("INSERT INTO notifications(id,user_id,type,subject,body,is_read,dedupe_key,created_at) VALUES (?,?,?,'Order received',?,false,?,now()) ON CONFLICT (dedupe_key) DO NOTHING",
                UUID.randomUUID(),event.userId(),"ORDER_CREATED","Your order "+orderId+" has been received.","order-created:"+orderId);
        try {
            kafka.send("artio.order.events",orderId,mapper.writeValueAsString(event))
                    .whenComplete((result, failure) -> {
                        if (failure != null) log.warn("Order event could not be published for {}", orderId, failure);
                    });
        }
        catch(JsonProcessingException e){log.warn("Could not serialize order event {}",orderId,e);}
        catch(RuntimeException e){log.warn("Order event could not be submitted for {}",orderId,e);}
        try { sendEmail(event.userId(),orderId); }
        catch(Exception e){log.warn("Order notification email could not be delivered for {}",orderId,e);}
    }
    private void sendEmail(UUID userId,String orderId) throws MessagingException {
        JavaMailSender sender=mailSender.getIfAvailable(); if(sender==null)return;
        var rows=jdbc.query("SELECT email FROM users WHERE id=?",(rs,n)->rs.getString(1),userId);if(rows.isEmpty())return;
        MimeMessage message=sender.createMimeMessage(); MimeMessageHelper helper=new MimeMessageHelper(message,false,"UTF-8");
        helper.setFrom(from);helper.setTo(rows.getFirst());helper.setSubject("Artio order received");helper.setText("Your order "+orderId+" has been received. Thank you for shopping with Artio.");sender.send(message);
    }
}
