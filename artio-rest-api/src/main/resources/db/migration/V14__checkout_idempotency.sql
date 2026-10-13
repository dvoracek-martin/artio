ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(128);
UPDATE orders SET idempotency_key = 'legacy:' || id::text;
ALTER TABLE orders ALTER COLUMN idempotency_key SET NOT NULL;
CREATE UNIQUE INDEX orders_user_idempotency_key_uq ON orders(user_id, idempotency_key);
