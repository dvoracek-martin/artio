ALTER TABLE cart_items DROP CONSTRAINT cart_items_pkey;
ALTER TABLE cart_items ADD COLUMN id UUID DEFAULT gen_random_uuid();
UPDATE cart_items SET id=gen_random_uuid() WHERE id IS NULL;
ALTER TABLE cart_items ALTER COLUMN id SET NOT NULL;
ALTER TABLE cart_items ADD PRIMARY KEY (id);
ALTER TABLE cart_items ADD COLUMN bundle_ref UUID;
ALTER TABLE cart_items ADD COLUMN bundle_id UUID;
ALTER TABLE cart_items ALTER COLUMN quantity TYPE NUMERIC(19,4) USING quantity::NUMERIC(19,4);
CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);
CREATE INDEX idx_cart_items_bundle ON cart_items(bundle_ref);
CREATE TABLE cart_bundle_lines (
  bundle_ref UUID PRIMARY KEY,
  cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
  bundle_id UUID NOT NULL,
  selected_options JSONB NOT NULL,
  quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
  market VARCHAR(32) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  jurisdiction VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE order_items ADD COLUMN bundle_id UUID;
ALTER TABLE order_items ADD COLUMN bundle_ref UUID;
ALTER TABLE order_items ALTER COLUMN quantity TYPE NUMERIC(19,4) USING quantity::NUMERIC(19,4);
