CREATE INDEX payment_webhook_receipts_processed_idx
    ON payment_webhook_receipts(processed_at)
    WHERE processed_at IS NOT NULL;
