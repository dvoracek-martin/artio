package io.artio.api.notifications;

import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers transactional order-email outbox records with bounded retries. */
@Component
public class OrderNotificationHandler {
    private static final Logger log = LoggerFactory.getLogger(OrderNotificationHandler.class);
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 12;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final boolean enabled;

    public OrderNotificationHandler(JdbcTemplate jdbc, ObjectProvider<JavaMailSender> mailSender,
            @Value("${artio.mail-from}") String from,
            @Value("${artio.notifications.email.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.mailSender = mailSender;
        this.from = from;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${artio.notifications.email.poll-interval-ms:30000}")
    public void deliverPendingBatch() {
        if (!enabled) return;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) return;

        jdbc.update("UPDATE notification_delivery_outbox SET status='PENDING',locked_at=NULL "
                + "WHERE status='PROCESSING' AND locked_at<now()-interval '5 minutes'");
        List<EmailDelivery> batch = jdbc.query("""
                WITH candidates AS (
                    SELECT d.id
                    FROM notification_delivery_outbox d
                    JOIN users u ON u.id=d.user_id
                    WHERE d.status='PENDING' AND d.next_attempt_at<=now()
                      AND u.active AND u.email NOT LIKE '%@privacy.artio.invalid'
                    ORDER BY d.created_at
                    FOR UPDATE OF d SKIP LOCKED
                    LIMIT ?
                ), claimed AS (
                    UPDATE notification_delivery_outbox d
                    SET status='PROCESSING',attempts=d.attempts+1,locked_at=now()
                    FROM candidates c
                    WHERE d.id=c.id
                    RETURNING d.id,d.user_id,d.order_id,d.subject,d.body,d.attempts
                )
                SELECT claimed.id,claimed.user_id,claimed.order_id,claimed.subject,claimed.body,u.email,claimed.attempts
                FROM claimed JOIN users u ON u.id=claimed.user_id
                """, (rs, row) -> new EmailDelivery(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("subject"), rs.getString("body"), rs.getString("email"), rs.getInt("attempts")), BATCH_SIZE);

        for (EmailDelivery delivery : batch) {
            try {
                send(sender, delivery);
                jdbc.update("UPDATE notification_delivery_outbox SET status='SENT',sent_at=now(),locked_at=NULL,"
                        + "subject='',body='',last_error_class=NULL WHERE id=? AND status='PROCESSING'", delivery.id());
            } catch (Exception failure) {
                boolean dead = delivery.attempts() >= MAX_ATTEMPTS;
                long delaySeconds = Math.min(3600L, 30L << Math.min(delivery.attempts() - 1, 7));
                jdbc.update("UPDATE notification_delivery_outbox SET status=?,locked_at=NULL,next_attempt_at=now()+(? * interval '1 second'),"
                                + "last_error_class=?,subject=CASE WHEN ? THEN '' ELSE subject END,body=CASE WHEN ? THEN '' ELSE body END "
                                + "WHERE id=? AND status='PROCESSING'",
                        dead ? "DEAD" : "PENDING", delaySeconds,
                        failure.getClass().getSimpleName().substring(0, Math.min(128, failure.getClass().getSimpleName().length())),
                        dead, dead, delivery.id());
                log.warn("Order email delivery failed for {} on attempt {} ({})",
                        delivery.orderId(), delivery.attempts(), failure.getClass().getSimpleName());
            }
        }
    }

    private void send(JavaMailSender sender, EmailDelivery delivery) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(from);
        helper.setTo(delivery.email());
        helper.setSubject(delivery.subject());
        helper.setText(delivery.body());
        sender.send(message);
    }

    private record EmailDelivery(UUID id, UUID userId, UUID orderId, String subject,
                                  String body, String email, int attempts) { }
}
