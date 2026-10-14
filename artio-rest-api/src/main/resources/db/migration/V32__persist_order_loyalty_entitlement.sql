-- Preserve the points promise made by the pre-entitlement implementation for existing orders.
-- New Community orders make no such promise; checkout explicitly snapshots the current local grant.
ALTER TABLE orders ADD COLUMN loyalty_eligible BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE orders SET loyalty_eligible = TRUE;
