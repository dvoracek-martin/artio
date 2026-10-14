package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.NotificationTransport.OutboundMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtensionNotificationTransportTests {
    private final ManagedExtensionInvoker extensions = mock(ManagedExtensionInvoker.class);
    private final ExtensionNotificationTransport transport = new ExtensionNotificationTransport(extensions, "notifications.mailer");

    @Test
    void sendsTheCoreDeliveryIdempotencyIdentityAndMapsProviderResults() throws Exception {
        var message = new OutboundMessage("delivery-123", "email", "buyer@example.test", "Receipt", "Hello",
                Map.of("orderId", "order-123"));
        when(extensions.invoke(eq("notifications.mailer"), eq("notification.channel.v1"), eq("deliver"), eq(message)))
                .thenReturn(new ObjectMapper().readTree("{\"accepted\":true,\"providerReference\":\"mail-123\"}"));

        var result = transport.deliver(message);

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerReference()).isEqualTo("mail-123");
        verify(extensions).invoke("notifications.mailer", "notification.channel.v1", "deliver", message);
    }

    @Test
    void rejectsMalformedProviderResponses() throws Exception {
        var message = new OutboundMessage("id", "email", "a@example.test", "s", "b", Map.of());
        when(extensions.invoke(eq("notifications.mailer"), eq("notification.channel.v1"), eq("deliver"), eq(message)))
                .thenReturn(new ObjectMapper().readTree("{\"accepted\":\"yes\"}"));

        assertThatThrownBy(() -> transport.deliver(message))
                .isInstanceOf(ExtensionRuntimePort.ExtensionProtocolException.class)
                .hasMessageContaining("accepted boolean");
    }
}
