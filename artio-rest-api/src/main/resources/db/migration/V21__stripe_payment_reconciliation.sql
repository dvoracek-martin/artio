ALTER TABLE payments
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN reconciliation_claimed_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_last_error VARCHAR(64);

CREATE INDEX idx_payments_reconciliation_due
    ON payments(reconciliation_available_at, created_at)
    WHERE method = 'STRIPE' AND status = 'PENDING';
