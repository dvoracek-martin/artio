CREATE TABLE notification_delivery_outbox (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    order_id UUID NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    last_error_class VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);

CREATE INDEX notification_delivery_pending_idx
    ON notification_delivery_outbox(next_attempt_at, created_at) WHERE status='PENDING';
CREATE INDEX notification_delivery_stale_claim_idx
    ON notification_delivery_outbox(locked_at) WHERE status='PROCESSING';
