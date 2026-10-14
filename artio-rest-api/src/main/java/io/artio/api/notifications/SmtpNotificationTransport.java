package io.artio.api.notifications;

import io.artio.extensions.commerce.NotificationTransport;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** SMTP adapter for the provider-neutral notification delivery port. */
@Component
@ConditionalOnProperty(name = "artio.notifications.email.provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpNotificationTransport implements NotificationTransport {
    private final JavaMailSender sender;
    private final String from;

    public SmtpNotificationTransport(JavaMailSender sender, @Value("${artio.mail-from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public DeliveryResult deliver(OutboundMessage outbound) {
        if (!"email".equalsIgnoreCase(outbound.channel())) {
            return new DeliveryResult(false, null, null, "UNSUPPORTED_CHANNEL");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(outbound.destination());
            helper.setSubject(outbound.subject());
            helper.setText(outbound.body());
            sender.send(message);
            return new DeliveryResult(true, message.getMessageID(), null, null);
        } catch (MessagingException failure) {
            throw new IllegalStateException("Could not prepare outbound email", failure);
        }
    }
}
