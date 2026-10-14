package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpNotificationTransportTests {
    @Test
    void sendsEmailThroughTheProviderNeutralMessageContract() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        var transport = new SmtpNotificationTransport(sender, "orders@example.test");

        var result = transport.deliver(new OutboundMessage("delivery-123", "email", "buyer@example.test",
                "Order received", "Thanks for your order", Map.of("orderId", "order-123")));

        assertThat(result.accepted()).isTrue();
        assertThat(mimeMessage.getFrom()[0].toString()).isEqualTo("orders@example.test");
        assertThat(mimeMessage.getAllRecipients()[0].toString()).isEqualTo("buyer@example.test");
        assertThat(mimeMessage.getSubject()).isEqualTo("Order received");
        assertThat(mimeMessage.getContent()).isEqualTo("Thanks for your order");
        verify(sender).send(mimeMessage);
    }

    @Test
    void rejectsUnsupportedChannelsWithoutCallingSmtp() {
        JavaMailSender sender = mock(JavaMailSender.class);
        var transport = new SmtpNotificationTransport(sender, "orders@example.test");

        var result = transport.deliver(new OutboundMessage("delivery-456", "sms", "+41000000000",
                "Order received", "Thanks", Map.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.failureCode()).isEqualTo("UNSUPPORTED_CHANNEL");
        verifyNoInteractions(sender);
    }
}
