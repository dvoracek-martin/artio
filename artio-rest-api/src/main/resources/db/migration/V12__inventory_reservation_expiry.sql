ALTER TABLE inventory_reservation ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE inventory_reservation SET expires_at = now() + INTERVAL '30 minutes';
ALTER TABLE inventory_reservation ALTER COLUMN expires_at SET NOT NULL;
CREATE INDEX inventory_reservation_expiry_idx ON inventory_reservation(expires_at)
    WHERE state = 'RESERVED';
