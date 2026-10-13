package io.artio.notifications;

import io.artio.kernel.Aggregate;
import java.time.Instant;
import java.util.UUID;

public final class Notification extends Aggregate<NotificationId> {
    private final UUID userId; private final NotificationType type; private final String subject; private final String body; private boolean read;
    public Notification(NotificationId id,UUID userId,NotificationType type,String subject,String body){super(id);if(userId==null)throw new IllegalArgumentException("User id is required");this.userId=userId;this.type=type;this.subject=subject;this.body=body;this.read=false;}
    public UUID userId(){return userId;} public NotificationType type(){return type;} public String subject(){return subject;} public String body(){return body;} public boolean read(){return read;} public void markRead(){read=true;}
}
