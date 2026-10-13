CREATE TABLE payment_webhook_receipts (
    provider VARCHAR(32) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    PRIMARY KEY (provider, event_id)
);

CREATE INDEX payment_webhook_receipts_received_idx ON payment_webhook_receipts(received_at);
