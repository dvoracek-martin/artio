package io.artio.notifications;
import java.util.UUID;
public record NotificationId(UUID value) { public NotificationId { if(value==null) throw new IllegalArgumentException("Notification id is required"); } }
