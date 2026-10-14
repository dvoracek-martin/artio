package io.artio.api.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import io.artio.api.extensions.ManagedExtensionInvoker;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.commerce.NotificationTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Delegates delivery to an installed notification.channel.v1 plugin; Core retains outbox/retry ownership. */
@Component
@ConditionalOnProperty(name = "artio.notifications.email.provider", havingValue = "extension")
public class ExtensionNotificationTransport implements NotificationTransport {
    private static final String EXTENSION_POINT = "notification.channel.v1";
    private final ManagedExtensionInvoker extensions;
    private final String pluginId;

    public ExtensionNotificationTransport(ManagedExtensionInvoker extensions,
            @Value("${artio.notifications.email.extension-plugin-id:}") String pluginId) {
        if (pluginId == null || !pluginId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) {
            throw new IllegalStateException("ARTIO_NOTIFICATIONS_EXTENSION_PLUGIN_ID must identify a notification plugin");
        }
        this.extensions = extensions;
        this.pluginId = pluginId;
    }

    @Override
    public DeliveryResult deliver(OutboundMessage message) {
        JsonNode response = extensions.invoke(pluginId, EXTENSION_POINT, "deliver", message);
        JsonNode accepted = response == null ? null : response.get("accepted");
        if (accepted == null || !accepted.isBoolean()) throw new ExtensionRuntimePort.ExtensionProtocolException(
                "Notification plugin response must include an accepted boolean");
        return new DeliveryResult(accepted.asBoolean(), text(response, "providerReference"),
                text(response, "retryAfter"), text(response, "failureCode"));
    }

    private static String text(JsonNode response, String field) {
        JsonNode value = response.get(field);
        return value == null || value.isNull() ? null : value.isTextual() ? value.asText() : invalidField(field);
    }

    private static String invalidField(String field) {
        throw new ExtensionRuntimePort.ExtensionProtocolException("Notification plugin returned an invalid " + field);
    }
}
