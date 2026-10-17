-- Artio current development schema snapshot. It consolidates the complete latest pre-production schema.
-- Development maintains one snapshot; versioned database upgrades begin with production releases.

-- ===== Legacy V1__catalog_schema =====
CREATE TABLE catalog_product (
    id UUID PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    description VARCHAR(5000),
    sku VARCHAR(50) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE catalog_product ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE catalog_product ADD COLUMN scheduled_publish_at TIMESTAMPTZ;
CREATE INDEX catalog_product_due_publication_idx ON catalog_product(scheduled_publish_at, id)
    WHERE scheduled_publish_at IS NOT NULL AND active = FALSE AND archived_at IS NULL;

CREATE TABLE catalog_variant (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES catalog_product(id),
    name VARCHAR(500) NOT NULL,
    sku VARCHAR(50) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX catalog_variant_product_id_idx ON catalog_variant(product_id);
ALTER TABLE catalog_variant ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE UNIQUE INDEX catalog_variant_combination_unique_idx ON catalog_variant(product_id, attributes)
    WHERE attributes <> '{}'::jsonb;

CREATE TABLE catalog_product_media (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    storage_key VARCHAR(80) NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    width INTEGER NOT NULL CHECK (width > 0),
    height INTEGER NOT NULL CHECK (height > 0),
    alt_text VARCHAR(500) NOT NULL DEFAULT '',
    position INTEGER NOT NULL CHECK (position >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX catalog_product_media_order_idx ON catalog_product_media(product_id, position, created_at, id);

-- ===== Legacy V2__catalog_taxonomy_and_attributes =====
CREATE TABLE catalog_category (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    parent_id UUID REFERENCES catalog_category(id),
    active BOOLEAN NOT NULL
);
CREATE INDEX catalog_category_parent_id_idx ON catalog_category(parent_id);

CREATE TABLE catalog_brand (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    description VARCHAR(2000),
    active BOOLEAN NOT NULL
);

CREATE TABLE catalog_attribute_definition (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(200) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'BOOLEAN')),
    required BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL
);

ALTER TABLE catalog_product ADD COLUMN brand_id UUID REFERENCES catalog_brand(id);
CREATE INDEX catalog_product_brand_id_idx ON catalog_product(brand_id);

CREATE TABLE catalog_product_category (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES catalog_category(id),
    PRIMARY KEY (product_id, category_id)
);
CREATE INDEX catalog_product_category_category_id_idx ON catalog_product_category(category_id);

CREATE TABLE catalog_product_attribute (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    attribute_code VARCHAR(64) NOT NULL REFERENCES catalog_attribute_definition(code),
    value VARCHAR(2000) NOT NULL,
    PRIMARY KEY (product_id, attribute_code)
);


-- ===== Legacy V3__pricing_schema =====
CREATE TABLE pricing_price (
    id UUID PRIMARY KEY,
    sellable_id UUID NOT NULL,
    market VARCHAR(32) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    currency CHAR(3) NOT NULL,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL,
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX pricing_price_lookup_idx ON pricing_price(sellable_id, market, channel, active, valid_from);

CREATE TABLE pricing_discount_rule (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    sellable_id UUID,
    market VARCHAR(32),
    percentage NUMERIC(7, 4) NOT NULL CHECK (percentage BETWEEN 0 AND 100),
    priority INTEGER NOT NULL CHECK (priority >= 0),
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    usage_limit INTEGER CHECK (usage_limit IS NULL OR usage_limit > 0),
    active BOOLEAN NOT NULL,
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX pricing_discount_rule_lookup_idx ON pricing_discount_rule(active, market, valid_from, priority);

CREATE TABLE pricing_tax_rule (
    id UUID PRIMARY KEY,
    jurisdiction VARCHAR(32) NOT NULL,
    percentage NUMERIC(7, 4) NOT NULL CHECK (percentage BETWEEN 0 AND 100),
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_until TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL,
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX pricing_tax_rule_lookup_idx ON pricing_tax_rule(jurisdiction, active, valid_from);


-- ===== Legacy V4__inventory_schema =====
CREATE TABLE inventory_warehouse (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    location VARCHAR(300) NOT NULL,
    active BOOLEAN NOT NULL
);

CREATE TABLE inventory_stock_level (
    id UUID PRIMARY KEY,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    sellable_id UUID NOT NULL,
    on_hand NUMERIC(19, 4) NOT NULL CHECK (on_hand >= 0),
    reserved NUMERIC(19, 4) NOT NULL CHECK (reserved >= 0 AND reserved <= on_hand),
    UNIQUE (warehouse_id, sellable_id)
);
CREATE INDEX inventory_stock_sellable_idx ON inventory_stock_level(sellable_id, warehouse_id);

CREATE TABLE inventory_stock_movement (
    id UUID PRIMARY KEY,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    sellable_id UUID NOT NULL,
    movement_type VARCHAR(24) NOT NULL CHECK (movement_type IN ('RECEIPT','ADJUSTMENT','RESERVATION','RELEASE','ALLOCATION')),
    on_hand_delta NUMERIC(19,4) NOT NULL,
    reserved_delta NUMERIC(19,4) NOT NULL,
    on_hand_after NUMERIC(19,4) NOT NULL CHECK (on_hand_after >= 0),
    reserved_after NUMERIC(19,4) NOT NULL CHECK (reserved_after >= 0 AND reserved_after <= on_hand_after),
    reason VARCHAR(500),
    actor_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX inventory_stock_movement_history_idx ON inventory_stock_movement(warehouse_id,sellable_id,occurred_at DESC,id DESC);

CREATE TABLE inventory_reservation (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    sellable_id UUID NOT NULL,
    quantity NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    allocated_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (allocated_quantity >= 0 AND allocated_quantity <= quantity),
    released_quantity NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (released_quantity >= 0 AND released_quantity <= quantity),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'ALLOCATED', 'RELEASED')),
    CHECK (allocated_quantity + released_quantity <= quantity),
    UNIQUE (order_id, warehouse_id, sellable_id)
);
CREATE INDEX inventory_reservation_order_idx ON inventory_reservation(order_id);


-- ===== Legacy V5__bundles_schema =====
CREATE TABLE commerce_bundle (
    id UUID PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    sku VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(16) NOT NULL CHECK (type IN ('FIXED', 'CONFIGURABLE')),
    active BOOLEAN NOT NULL,
    pricing_mode VARCHAR(32) NOT NULL CHECK (pricing_mode IN ('COMPONENT_SUM', 'FIXED_PRICE', 'PERCENTAGE_OFF_COMPONENTS')),
    fixed_amount NUMERIC(19, 4),
    currency CHAR(3),
    discount_percentage NUMERIC(7, 4),
    CHECK ((pricing_mode = 'FIXED_PRICE' AND fixed_amount IS NOT NULL AND currency IS NOT NULL AND discount_percentage IS NULL)
        OR (pricing_mode = 'PERCENTAGE_OFF_COMPONENTS' AND fixed_amount IS NULL AND currency IS NULL AND discount_percentage BETWEEN 0 AND 100)
        OR (pricing_mode = 'COMPONENT_SUM' AND fixed_amount IS NULL AND currency IS NULL AND discount_percentage IS NULL))
);

CREATE TABLE commerce_bundle_option_group (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    minimum_selections INTEGER NOT NULL CHECK (minimum_selections >= 0),
    maximum_selections INTEGER NOT NULL CHECK (maximum_selections >= minimum_selections),
    PRIMARY KEY (bundle_id, code)
);

CREATE TABLE commerce_bundle_component (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    group_code VARCHAR(64),
    sellable_id UUID NOT NULL,
    quantity NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    FOREIGN KEY (bundle_id, group_code) REFERENCES commerce_bundle_option_group(bundle_id, code) ON DELETE CASCADE
);
CREATE INDEX commerce_bundle_component_sellable_idx ON commerce_bundle_component(sellable_id);


-- ===== Legacy V6__users_orders =====
CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  first_name VARCHAR(255) NOT NULL,
  last_name VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'CUSTOMER',
  permissions TEXT NOT NULL DEFAULT '',
  email_verified BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL,
  last_login TIMESTAMPTZ
);
CREATE TABLE artio_email_change_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  new_email VARCHAR(255) NOT NULL,
  token_sha256 CHAR(64) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  used_at TIMESTAMPTZ
);
CREATE INDEX artio_email_change_tokens_user_idx
  ON artio_email_change_tokens(user_id, expires_at DESC)
  WHERE used_at IS NULL;
CREATE TABLE carts (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL);
CREATE TABLE cart_items (cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE, variant_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0), currency CHAR(3) NOT NULL, PRIMARY KEY (cart_id, variant_id));
CREATE TABLE orders (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), status VARCHAR(32) NOT NULL, subtotal NUMERIC(19,4) NOT NULL, tax NUMERIC(19,4) NOT NULL, shipping NUMERIC(19,4) NOT NULL, total NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL, shipping_address TEXT NOT NULL, billing_address TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, shipping_method_id VARCHAR(120), shipping_parcel_weight_kg NUMERIC(12,3), shipment_reference VARCHAR(255), shipping_label_url TEXT);
CREATE TABLE order_items (id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE, variant_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL);
CREATE TABLE pricing_discount_redemptions (
  discount_rule_id UUID NOT NULL REFERENCES pricing_discount_rule(id),
  order_id UUID NOT NULL REFERENCES orders(id),
  redeemed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(discount_rule_id,order_id)
);
CREATE INDEX pricing_discount_redemptions_order_idx ON pricing_discount_redemptions(order_id);
CREATE TABLE order_shipments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    tracking_number VARCHAR(255),
    shipment_reference VARCHAR(255),
    shipping_method_id VARCHAR(120),
    label_url TEXT,
    idempotency_key VARCHAR(128),
    request_fingerprint CHAR(64) CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[a-f0-9]{64}$'),
    provider_instance_id UUID,
    provider_release_id BIGINT,
    provider_configuration_generation INTEGER CHECK (provider_configuration_generation >= 0),
    provider_account_fingerprint CHAR(64) CHECK (provider_account_fingerprint IS NULL OR provider_account_fingerprint ~ '^[a-f0-9]{64}$'),
    provider_scope JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(provider_scope) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, tracking_number),
    CHECK (tracking_number IS NULL OR btrim(tracking_number) <> ''),
    CHECK ((idempotency_key IS NULL AND request_fingerprint IS NULL) OR (idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL)),
    CHECK ((provider_instance_id IS NULL AND provider_release_id IS NULL AND provider_configuration_generation IS NULL)
        OR (provider_instance_id IS NOT NULL AND provider_release_id IS NOT NULL AND provider_configuration_generation IS NOT NULL))
);
CREATE INDEX order_shipments_order_idx ON order_shipments(order_id, created_at, id);
CREATE UNIQUE INDEX uq_order_shipments_idempotency ON order_shipments(order_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE TABLE order_shipment_items (
    shipment_id UUID NOT NULL REFERENCES order_shipments(id) ON DELETE CASCADE,
    order_item_id UUID NOT NULL REFERENCES order_items(id),
    quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (shipment_id, order_item_id)
);
CREATE TABLE order_returns (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  customer_id UUID NOT NULL REFERENCES users(id),
  status VARCHAR(20) NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED','COMPLETED')),
  reason VARCHAR(1000) NOT NULL,
  customer_note VARCHAR(2000),
  staff_note VARCHAR(2000),
  idempotency_key VARCHAR(128) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_order_returns_order ON order_returns(order_id, created_at DESC);
CREATE TABLE order_return_items (
  id UUID PRIMARY KEY,
  return_id UUID NOT NULL REFERENCES order_returns(id) ON DELETE CASCADE,
  order_item_id UUID NOT NULL REFERENCES order_items(id),
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  restock_warehouse_id UUID REFERENCES inventory_warehouse(id),
  restocked_at TIMESTAMPTZ,
  UNIQUE (return_id, order_item_id)
);
CREATE TABLE order_return_events (
  id UUID PRIMARY KEY,
  return_id UUID NOT NULL REFERENCES order_returns(id) ON DELETE CASCADE,
  actor_id UUID REFERENCES users(id),
  event_type VARCHAR(32) NOT NULL,
  note VARCHAR(2000),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE order_timeline_events (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  actor_id UUID REFERENCES users(id),
  event_type VARCHAR(40) NOT NULL,
  message VARCHAR(2000) NOT NULL,
  customer_visible BOOLEAN NOT NULL DEFAULT FALSE,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_timeline_order ON order_timeline_events(order_id, occurred_at, id);
CREATE TABLE shipment_tracking_events (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  tracking_reference VARCHAR(255) NOT NULL,
  carrier_status VARCHAR(80) NOT NULL,
  last_location VARCHAR(200),
  occurred_at TIMESTAMPTZ,
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipment_tracking_order ON shipment_tracking_events(order_id,checked_at DESC);
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_carts_user ON carts(user_id);


-- ===== Legacy V7__payments_notifications =====
CREATE TABLE payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  user_id UUID NOT NULL REFERENCES users(id),
  amount NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
  captured_amount NUMERIC(19,4) CHECK (captured_amount IS NULL OR captured_amount >= 0),
  captured_currency CHAR(3),
  refunded_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
  reversed_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (reversed_amount >= 0),
  currency CHAR(3) NOT NULL,
  method VARCHAR(32) NOT NULL,
  payment_method_id VARCHAR(64) CHECK (payment_method_id IS NULL OR payment_method_id ~ '^[a-z][a-z0-9._-]{0,63}$'),
  payment_obligation_id UUID REFERENCES orders(id),
  status VARCHAR(32) NOT NULL,
  provider_plugin_id VARCHAR(128),
  provider_reference VARCHAR(255) NOT NULL,
  provider_transaction_reference VARCHAR(255),
  provider_instance_id UUID,
  provider_release_id BIGINT,
  provider_configuration_generation INTEGER CHECK (provider_configuration_generation >= 0),
  provider_account_fingerprint CHAR(64) CHECK (provider_account_fingerprint IS NULL OR provider_account_fingerprint ~ '^[a-f0-9]{64}$'),
  provider_scope JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(provider_scope) = 'object'),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK ((provider_instance_id IS NULL AND provider_release_id IS NULL AND provider_configuration_generation IS NULL)
      OR (provider_instance_id IS NOT NULL AND provider_release_id IS NOT NULL AND provider_configuration_generation IS NOT NULL)),
  CHECK ((captured_amount IS NULL AND captured_currency IS NULL) OR (captured_amount IS NOT NULL AND captured_currency IS NOT NULL)),
  CHECK (refunded_amount + reversed_amount <= COALESCE(captured_amount,amount))
);
CREATE UNIQUE INDEX uq_payments_provider_reference ON payments(provider_plugin_id,provider_instance_id,provider_account_fingerprint,provider_reference) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX uq_payments_provider_transaction_reference ON payments(provider_plugin_id,provider_instance_id,provider_account_fingerprint,provider_transaction_reference)
    WHERE provider_transaction_reference IS NOT NULL;
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_provider_owner ON payments(provider_instance_id,created_at DESC) WHERE provider_instance_id IS NOT NULL;
-- The selected provider and stable idempotency identity are durable before invoking a PSP.
CREATE TABLE payment_attempts (
  attempt_id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  payment_obligation_id UUID NOT NULL REFERENCES orders(id),
  provider_plugin_id VARCHAR(128) NOT NULL,
  provider_instance_id UUID,
  provider_release_id BIGINT,
  configuration_generation INTEGER NOT NULL DEFAULT 0 CHECK (configuration_generation >= 0),
  provider_account_fingerprint CHAR(64) CHECK (provider_account_fingerprint IS NULL OR provider_account_fingerprint ~ '^[a-f0-9]{64}$'),
  provider_scope JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(provider_scope) = 'object'),
  payment_method_id VARCHAR(64),
  idempotency_key VARCHAR(200) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('CREATING','PENDING','UNKNOWN','FAILED','CAPTURED','CANCELLED')),
  provider_reference VARCHAR(255),
  checkout_url TEXT,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(order_id,idempotency_key),
  CHECK ((provider_instance_id IS NULL AND provider_release_id IS NULL)
      OR (provider_instance_id IS NOT NULL AND provider_release_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_payment_attempt_provider_reference ON payment_attempts(provider_plugin_id,provider_instance_id,provider_account_fingerprint,provider_reference) NULLS NOT DISTINCT
    WHERE provider_reference IS NOT NULL;
ALTER TABLE payments ADD COLUMN payment_attempt_id UUID UNIQUE REFERENCES payment_attempts(attempt_id);
-- A verified provider capture is a financial fact even when it cannot settle an order automatically.
CREATE TABLE payment_settlements (
  settlement_id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  provider_event_id VARCHAR(255) NOT NULL,
  provider_reference VARCHAR(255) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
  currency CHAR(3) NOT NULL,
  disposition VARCHAR(32) NOT NULL CHECK (disposition IN ('APPLIED','LATE_CAPTURE','OVERPAYMENT','UNDERPAYMENT','CURRENCY_MISMATCH','CHARGEBACK_OPEN','CHARGEBACK_WON','CHARGEBACK_LOST','PAYMENT_REVERSAL')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(payment_id, provider_event_id)
);
CREATE INDEX idx_payment_settlements_payment ON payment_settlements(payment_id, created_at DESC);
CREATE FUNCTION payment_settlements_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'payment_settlements is immutable'; END $$;
CREATE TRIGGER payment_settlements_immutable
    BEFORE UPDATE OR DELETE ON payment_settlements FOR EACH ROW EXECUTE FUNCTION payment_settlements_immutable();
CREATE TABLE payment_reconciliation_cases (
  case_id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  order_id UUID NOT NULL REFERENCES orders(id),
  settlement_id UUID NOT NULL UNIQUE REFERENCES payment_settlements(settlement_id),
  case_type VARCHAR(32) NOT NULL CHECK (case_type IN ('LATE_CAPTURE','OVERPAYMENT','UNDERPAYMENT','CURRENCY_MISMATCH','CHARGEBACK','PAYMENT_REVERSAL')),
  expected_amount NUMERIC(19,4) NOT NULL CHECK (expected_amount >= 0),
  captured_amount NUMERIC(19,4) NOT NULL CHECK (captured_amount >= 0),
  expected_currency CHAR(3) NOT NULL,
  captured_currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
  resolution_note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_payment_reconciliation_cases_open ON payment_reconciliation_cases(created_at) WHERE status='OPEN';
CREATE TABLE payment_refunds (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  idempotency_key VARCHAR(128) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
  provider_reference VARCHAR(255),
  tax_allocation_snapshot JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(payment_id,idempotency_key)
);
CREATE INDEX idx_payment_refunds_payment ON payment_refunds(payment_id,created_at);
CREATE FUNCTION payment_refund_tax_snapshot_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.tax_allocation_snapshot IS NOT NULL
      AND NEW.tax_allocation_snapshot IS DISTINCT FROM OLD.tax_allocation_snapshot THEN
    RAISE EXCEPTION 'payment refund tax allocation snapshot is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER payment_refund_tax_snapshot_immutable
    BEFORE UPDATE ON payment_refunds FOR EACH ROW EXECUTE FUNCTION payment_refund_tax_snapshot_immutable();
CREATE TABLE notifications (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type VARCHAR(40) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  body TEXT NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  dedupe_key VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_notifications_user ON notifications(user_id,created_at DESC);
CREATE UNIQUE INDEX uq_notifications_dedupe ON notifications(dedupe_key);


-- ===== Legacy V8__order_tracking =====
ALTER TABLE orders ADD COLUMN tracking_number VARCHAR(255);


-- ===== Legacy V9__bundle_cart_lines =====
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


-- ===== Legacy V10__enterprise_foundations =====
-- Artio is deployed as one isolated installation per customer. This migration
-- adds identity/security and optional commerce records without a SaaS tenant key.
CREATE TABLE mfa_methods (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  encrypted_secret TEXT NOT NULL,
  verified BOOLEAN NOT NULL DEFAULT FALSE,
  backup_code_hashes JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  verified_at TIMESTAMPTZ
);

CREATE TABLE oauth_identities (
  provider VARCHAR(32) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider_email VARCHAR(255) NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(provider,subject),
  UNIQUE(provider,user_id)
);

CREATE TABLE audit_logs (
  id UUID PRIMARY KEY,
  user_id UUID,
  action VARCHAR(16) NOT NULL,
  path VARCHAR(512) NOT NULL,
  status INTEGER NOT NULL,
  ip_address VARCHAR(64),
  user_agent VARCHAR(512),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_actor_time ON audit_logs(user_id,occurred_at DESC);
CREATE FUNCTION artio_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit_logs is append-only'; END $$;
CREATE TRIGGER audit_logs_immutable BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION artio_audit_immutable();
CREATE FUNCTION artio_inventory_movement_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'inventory_stock_movement is append-only'; END $$;
CREATE TRIGGER inventory_stock_movement_immutable BEFORE UPDATE OR DELETE ON inventory_stock_movement FOR EACH ROW EXECUTE FUNCTION artio_inventory_movement_immutable();

CREATE TABLE consent_records (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  essential BOOLEAN NOT NULL DEFAULT TRUE CHECK (essential),
  analytics BOOLEAN NOT NULL DEFAULT FALSE,
  marketing BOOLEAN NOT NULL DEFAULT FALSE,
  policy_version VARCHAR(32) NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_user ON consent_records(user_id,recorded_at DESC);

CREATE TABLE gift_cards (
  id UUID PRIMARY KEY,
  code_hash CHAR(64) NOT NULL UNIQUE,
  initial_balance NUMERIC(19,4) NOT NULL CHECK (initial_balance > 0),
  balance NUMERIC(19,4) NOT NULL CHECK (balance >= 0),
  currency CHAR(3) NOT NULL,
  expires_at TIMESTAMPTZ,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE payments ADD COLUMN gift_card_id UUID REFERENCES gift_cards(id);
CREATE TABLE gift_card_transactions (
  id UUID PRIMARY KEY,
  gift_card_id UUID NOT NULL REFERENCES gift_cards(id),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  order_id UUID,
  payment_refund_id UUID REFERENCES payment_refunds(id),
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('REDEEM','REFUND')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_gift_card_redemption_per_order ON gift_card_transactions(gift_card_id,order_id) WHERE order_id IS NOT NULL AND kind='REDEEM';
CREATE UNIQUE INDEX uq_gift_card_refund_transaction ON gift_card_transactions(payment_refund_id) WHERE payment_refund_id IS NOT NULL;

CREATE TABLE loyalty_accounts (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  points BIGINT NOT NULL DEFAULT 0,
  tier VARCHAR(16) NOT NULL DEFAULT 'BRONZE',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE loyalty_transactions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  order_id UUID,
  points BIGINT NOT NULL CHECK (points <> 0),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('EARN','REDEEM','ADJUST','REFUND')),
  refund_id UUID REFERENCES payment_refunds(id),
  idempotency_key VARCHAR(128),
  reason VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_loyalty_order_earn ON loyalty_transactions(user_id,order_id) WHERE order_id IS NOT NULL AND kind='EARN';
CREATE UNIQUE INDEX uq_loyalty_refund_reversal ON loyalty_transactions(refund_id) WHERE refund_id IS NOT NULL;
CREATE UNIQUE INDEX uq_loyalty_idempotency_key ON loyalty_transactions(user_id,kind,idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE exchange_rates (
  base_currency CHAR(3) NOT NULL,
  quote_currency CHAR(3) NOT NULL,
  rate NUMERIC(20,10) NOT NULL CHECK (rate > 0),
  valid_from TIMESTAMPTZ NOT NULL,
  source VARCHAR(64) NOT NULL,
  PRIMARY KEY(base_currency,quote_currency,valid_from),
  CHECK (base_currency <> quote_currency)
);

CREATE TABLE product_interactions (
  id UUID PRIMARY KEY,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  variant_id UUID NOT NULL,
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('VIEW','CART','PURCHASE')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_interactions_recommendations ON product_interactions(kind,occurred_at DESC);
ALTER TABLE product_interactions ADD COLUMN order_id UUID;
CREATE UNIQUE INDEX uq_purchase_interaction_order ON product_interactions(user_id,order_id,variant_id) WHERE kind='PURCHASE' AND order_id IS NOT NULL;

CREATE TABLE fraud_reviews (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
  signals JSONB NOT NULL,
  decision VARCHAR(16) NOT NULL CHECK (decision IN ('ALLOW','REVIEW','HOLD')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(order_id)
);

CREATE TABLE subscriptions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  frequency VARCHAR(16) NOT NULL CHECK (frequency IN ('WEEKLY','MONTHLY','QUARTERLY')),
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
  items JSONB NOT NULL,
  next_billing_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_due ON subscriptions(next_billing_at) WHERE status='ACTIVE';


-- ===== Legacy V11__localized_catalog_content =====
CREATE TABLE catalog_product_translations (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de','it','fr','en')),
    name VARCHAR(500) NOT NULL,
    description VARCHAR(5000),
    seo_slug VARCHAR(160),
    seo_title VARCHAR(180),
    seo_description VARCHAR(320),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (seo_slug IS NULL OR seo_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    PRIMARY KEY (product_id, locale)
);

CREATE INDEX catalog_product_translations_locale_idx ON catalog_product_translations(locale, product_id);
CREATE UNIQUE INDEX catalog_product_translations_seo_slug_idx ON catalog_product_translations(locale, seo_slug)
    WHERE seo_slug IS NOT NULL;


-- ===== Legacy V12__inventory_reservation_expiry =====
ALTER TABLE inventory_reservation ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE inventory_reservation SET expires_at = now() + INTERVAL '30 minutes';
ALTER TABLE inventory_reservation ALTER COLUMN expires_at SET NOT NULL;
CREATE INDEX inventory_reservation_expiry_idx ON inventory_reservation(expires_at)
    WHERE state = 'RESERVED';


-- ===== Legacy V13__order_tax_policy_snapshots =====
ALTER TABLE orders
    ADD COLUMN shipping_country CHAR(2),
    ADD COLUMN market VARCHAR(32),
    ADD COLUMN tax_jurisdiction VARCHAR(32),
    ADD COLUMN tax_snapshot JSONB;

UPDATE orders
SET shipping_country = 'ZZ',
    market = 'LEGACY',
    tax_jurisdiction = 'LEGACY',
    tax_snapshot = jsonb_build_object('schemaVersion', 0, 'provenance', 'historical policy details were not stored');

ALTER TABLE orders
    ALTER COLUMN shipping_country SET NOT NULL,
    ALTER COLUMN market SET NOT NULL,
    ALTER COLUMN tax_jurisdiction SET NOT NULL,
    ALTER COLUMN tax_snapshot SET NOT NULL;


-- ===== Legacy V14__checkout_idempotency =====
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(128);
UPDATE orders SET idempotency_key = 'legacy:' || id::text;
ALTER TABLE orders ALTER COLUMN idempotency_key SET NOT NULL;
CREATE UNIQUE INDEX orders_user_idempotency_key_uq ON orders(user_id, idempotency_key);


-- ===== Legacy V15__extension_registry_and_configuration =====
CREATE TABLE extension_plugins (
    plugin_id VARCHAR(128) PRIMARY KEY,
    manifest JSONB NOT NULL,
    image_reference VARCHAR(512) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN
        ('VALIDATED','CONFIGURED','INSTALLED','ENABLED','DISABLED','UNHEALTHY','UPDATING','FAILED','UNINSTALLED')),
    public_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    encrypted_secrets JSONB NOT NULL DEFAULT '{}'::jsonb,
    configuration_version INTEGER NOT NULL DEFAULT 0 CHECK (configuration_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extension_audit_events (
    id UUID PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL,
    actor_user_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX extension_audit_plugin_time_idx ON extension_audit_events(plugin_id, occurred_at DESC);
CREATE FUNCTION artio_extension_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'extension_audit_events is append-only'; END $$;
CREATE TRIGGER extension_audit_events_immutable BEFORE UPDATE OR DELETE ON extension_audit_events FOR EACH ROW EXECUTE FUNCTION artio_extension_audit_immutable();


-- ===== Legacy V16__payment_webhook_receipts =====
CREATE TABLE payment_webhook_receipts (
    provider VARCHAR(32) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    PRIMARY KEY (provider, event_id)
);

CREATE INDEX payment_webhook_receipts_received_idx ON payment_webhook_receipts(received_at);


-- ===== Legacy V17__effective_tax_categories_and_swiss_rates =====
ALTER TABLE pricing_tax_rule
    ADD COLUMN tax_category VARCHAR(24) NOT NULL DEFAULT 'STANDARD'
        CHECK (tax_category IN ('STANDARD', 'REDUCED', 'ACCOMMODATION'));

CREATE INDEX pricing_tax_category_lookup_idx
    ON pricing_tax_rule(jurisdiction, tax_category, active, valid_from);

CREATE TABLE pricing_sellable_tax_category (
    sellable_id UUID PRIMARY KEY,
    category VARCHAR(24) NOT NULL
        CHECK (category IN ('STANDARD', 'REDUCED', 'ACCOMMODATION')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO pricing_tax_rule(id, jurisdiction, tax_category, percentage, valid_from, valid_until, active)
VALUES
    ('00000000-0000-0000-0000-000000000081', 'CH', 'STANDARD', 8.1, '2024-01-01T00:00:00Z', NULL, TRUE),
    ('00000000-0000-0000-0000-000000000026', 'CH', 'REDUCED', 2.6, '2024-01-01T00:00:00Z', NULL, TRUE),
    ('00000000-0000-0000-0000-000000000038', 'CH', 'ACCOMMODATION', 3.8, '2024-01-01T00:00:00Z', NULL, TRUE);


-- ===== Legacy V18__transactional_event_outbox =====
CREATE TABLE event_outbox (
    id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    last_error VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    dead_at TIMESTAMPTZ
);

CREATE INDEX event_outbox_pending_idx ON event_outbox(available_at, created_at)
    WHERE status='PENDING';
CREATE INDEX event_outbox_stale_claim_idx ON event_outbox(locked_at)
    WHERE status='PROCESSING';
CREATE INDEX event_outbox_dead_idx ON event_outbox(dead_at DESC)
    WHERE status='DEAD';

CREATE TABLE event_inbox (
    consumer_name VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    aggregate_id VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE INDEX event_inbox_processed_idx ON event_inbox(processed_at);


-- ===== Legacy V19__durable_order_email_outbox =====
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


-- ===== Legacy V20__price_tax_inclusion_mode =====
ALTER TABLE pricing_price
    ADD COLUMN prices_include_tax BOOLEAN NOT NULL DEFAULT FALSE;


-- ===== Legacy V21__stripe_payment_reconciliation =====
ALTER TABLE payments
    ADD COLUMN reconciliation_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN reconciliation_claimed_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_last_error VARCHAR(64);

CREATE INDEX idx_payments_reconciliation_due
    ON payments(reconciliation_available_at, created_at)
    WHERE method = 'EXTENSION' AND status = 'PENDING';


-- ===== Legacy V22__bundle_localizations =====
CREATE TABLE commerce_bundle_translations (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de', 'it', 'fr', 'en')),
    name VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bundle_id, locale)
);

CREATE TABLE commerce_bundle_option_group_translations (
    bundle_id UUID NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de', 'it', 'fr', 'en')),
    name VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bundle_id, group_code, locale),
    FOREIGN KEY (bundle_id, group_code) REFERENCES commerce_bundle_option_group(bundle_id, code) ON DELETE CASCADE
);


-- ===== Legacy V23__stripe_webhook_receipt_retention =====
CREATE INDEX payment_webhook_receipts_processed_idx
    ON payment_webhook_receipts(processed_at)
    WHERE processed_at IS NOT NULL;


-- ===== Legacy V24__extension_credential_rotation =====
ALTER TABLE extension_plugins
    ADD COLUMN credential_generation INTEGER NOT NULL DEFAULT 1 CHECK (credential_generation > 0),
    ADD COLUMN rotation_was_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN rotation_previous_state VARCHAR(16);

ALTER TABLE extension_plugins DROP CONSTRAINT extension_plugins_lifecycle_state_check;
ALTER TABLE extension_plugins ADD CONSTRAINT extension_plugins_lifecycle_state_check CHECK (lifecycle_state IN
    ('VALIDATED','CONFIGURED','INSTALLED','ENABLED','DISABLED','UNHEALTHY','UPDATING','ROTATING','FAILED','UNINSTALLED'));


-- ===== Legacy V25__extension_release_history =====
CREATE TABLE extension_plugin_releases (
    release_id BIGSERIAL PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    version VARCHAR(64) NOT NULL,
    image_reference VARCHAR(512) NOT NULL,
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plugin_id, version)
);

CREATE TABLE extension_plugin_release_provenance (
    release_id BIGINT PRIMARY KEY REFERENCES extension_plugin_releases(release_id),
    provenance VARCHAR(32) NOT NULL CHECK (provenance IN ('OFFICIAL_MARKETPLACE','CUSTOM_REGISTRY','CUSTOM_LOCAL_ARCHIVE')),
    descriptor_digest CHAR(64),
    descriptor_snapshot JSONB CHECK (descriptor_snapshot IS NULL OR jsonb_typeof(descriptor_snapshot)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((provenance='OFFICIAL_MARKETPLACE' AND descriptor_digest ~ '^[a-f0-9]{64}$' AND descriptor_snapshot IS NOT NULL)
        OR (provenance<>'OFFICIAL_MARKETPLACE' AND descriptor_digest IS NULL))
);

INSERT INTO extension_plugin_releases(plugin_id, version, image_reference, manifest)
SELECT plugin_id, manifest->>'version', image_reference, manifest
FROM extension_plugins
WHERE lifecycle_state <> 'UNINSTALLED'
ON CONFLICT (plugin_id, version) DO NOTHING;


-- ===== Legacy V26__first_run_setup =====
CREATE TABLE artio_first_run_setup (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    setup_token_sha256 CHAR(64) NOT NULL,
    draft JSONB NOT NULL DEFAULT '{}'::jsonb,
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE artio_installation_settings (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    country_code CHAR(2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE artio_distribution_plugin_provisioning (
    distribution_id VARCHAR(120) NOT NULL,
    plugin_id VARCHAR(160) NOT NULL,
    optional BOOLEAN NOT NULL,
    provisioning_status VARCHAR(40) NOT NULL CHECK (provisioning_status IN (
        'WAITING_FOR_RELEASE','CONFIGURATION_REQUIRED','READY_TO_INSTALL','RETRY_REQUIRED',
        'INSTALLED_DISABLED','ACTIVE','UNINSTALLED_BY_ADMIN','LIFECYCLE_IN_PROGRESS')),
    lifecycle_state VARCHAR(32),
    reason VARCHAR(500) NOT NULL,
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (distribution_id, plugin_id)
);


-- ===== Legacy V27__system_settings =====
ALTER TABLE artio_installation_settings
    ADD COLUMN store_name VARCHAR(120) NOT NULL DEFAULT 'Artio Store',
    ADD COLUMN available_languages JSONB NOT NULL DEFAULT '["de","fr","it","en"]'::jsonb,
    ADD COLUMN external_base_url TEXT,
    ADD COLUMN distribution_id VARCHAR(80) NOT NULL DEFAULT 'switzerland-v1';


-- Consolidated current schema fragment (former V2__kafka_consumer_dlq_operations.sql); no upgrade history is retained in development.
CREATE TABLE kafka_consumer_dead_letters (
    id UUID PRIMARY KEY,
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK (source_partition >= 0),
    source_offset BIGINT NOT NULL CHECK (source_offset >= 0),
    event_id VARCHAR(128),
    event_type VARCHAR(128),
    record_key VARCHAR(512),
    encrypted_payload TEXT,
    payload_is_null BOOLEAN NOT NULL DEFAULT FALSE,
    schema_version VARCHAR(32),
    error_class VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DEAD' CHECK (status IN ('DEAD','REPLAY_REQUESTED','REPLAYING','REPLAYED')),
    replay_attempts INTEGER NOT NULL DEFAULT 0 CHECK (replay_attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    replayed_at TIMESTAMPTZ,
    last_replay_error VARCHAR(128),
    dead_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_topic, source_partition, source_offset)
);

CREATE INDEX kafka_consumer_dead_letters_status_idx
    ON kafka_consumer_dead_letters(status, available_at, dead_at DESC);



-- Consolidated current schema fragment (former V28__inventory_low_stock_policies.sql); no upgrade history is retained in development.
CREATE TABLE inventory_low_stock_policy (
    id UUID PRIMARY KEY,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    sellable_id UUID NOT NULL,
    threshold_quantity NUMERIC(15,4) NOT NULL CHECK (threshold_quantity >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (warehouse_id, sellable_id)
);

CREATE INDEX inventory_low_stock_policy_active_idx
    ON inventory_low_stock_policy (warehouse_id, sellable_id)
    WHERE active = TRUE;



-- Consolidated current schema fragment (former V29__catalog_product_seo_metadata.sql); no upgrade history is retained in development.
ALTER TABLE catalog_product
    ADD COLUMN seo_slug VARCHAR(160),
    ADD COLUMN seo_title VARCHAR(180),
    ADD COLUMN seo_description VARCHAR(320);

ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_seo_slug_format CHECK (
        seo_slug IS NULL OR seo_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    );

CREATE UNIQUE INDEX catalog_product_seo_slug_unique_idx
    ON catalog_product (seo_slug)
    WHERE seo_slug IS NOT NULL;



-- Consolidated current schema fragment (former V30__catalog_product_archive_state.sql); no upgrade history is retained in development.
ALTER TABLE catalog_product
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE INDEX catalog_product_archived_idx
    ON catalog_product (archived_at, updated_at DESC)
    WHERE archived_at IS NOT NULL;



-- Consolidated current schema fragment (former V31__local_signed_entitlements.sql); no upgrade history is retained in development.
CREATE TABLE artio_license_state (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    installation_id UUID NOT NULL DEFAULT gen_random_uuid(),
    signed_document TEXT,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    last_refresh_attempt_at TIMESTAMPTZ,
    last_refresh_success_at TIMESTAMPTZ,
    refresh_status VARCHAR(32) NOT NULL DEFAULT 'NEVER' CHECK (refresh_status IN
        ('NEVER','IN_PROGRESS','SUCCESS','UNAVAILABLE','INVALID_RESPONSE','INVALID_CONFIGURATION')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (signed_document IS NULL OR octet_length(signed_document) <= 65536)
);

INSERT INTO artio_license_state(singleton_id) VALUES (TRUE);



-- Consolidated current schema fragment (former V32__persist_order_loyalty_entitlement.sql); no upgrade history is retained in development.
-- Preserve the points promise made by the pre-entitlement implementation for existing orders.
-- New Community orders make no such promise; checkout explicitly snapshots the current local grant.
ALTER TABLE orders ADD COLUMN loyalty_eligible BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE orders SET loyalty_eligible = TRUE;



-- Consolidated current schema fragment (former V33__restore_legacy_admin_permission_defaults.sql); no upgrade history is retained in development.
-- Existing administrators without an explicit permission list keep the access they had before permission enforcement.
UPDATE users
SET permissions = '*'
WHERE role = 'ADMIN'
  AND btrim(permissions) = '';



-- Consolidated current schema fragment (former V34__prevent_overlapping_active_tax_rules.sql); no upgrade history is retained in development.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE pricing_tax_rule
    ADD CONSTRAINT pricing_tax_rule_active_period_excl
    EXCLUDE USING gist (
        jurisdiction WITH =,
        tax_category WITH =,
        tstzrange(valid_from, valid_until, '[)') WITH &&
    ) WHERE (active);



-- Consolidated current schema fragment (former V35__bound_swiss_accommodation_rate_period.sql); no upgrade history is retained in development.
UPDATE pricing_tax_rule
SET valid_until = TIMESTAMPTZ '2028-01-01 00:00:00+00'
WHERE id = '00000000-0000-0000-0000-000000000038'
  AND jurisdiction = 'CH'
  AND tax_category = 'ACCOMMODATION'
  AND valid_from = TIMESTAMPTZ '2024-01-01 00:00:00+00'
  AND valid_until IS NULL;



-- Consolidated current schema fragment (former V36__market_specific_bundle_fixed_prices.sql); no upgrade history is retained in development.
CREATE TABLE commerce_bundle_market_price (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    market CHAR(2) NOT NULL CHECK (market = upper(market) AND market ~ '^[A-Z]{2}$'),
    fixed_amount NUMERIC(19, 4) NOT NULL CHECK (fixed_amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (bundle_id, market)
);



-- Consolidated current schema fragment (former V37__plugin_instance_identity_and_composition.sql); no upgrade history is retained in development.
-- Keep package, immutable release and configured runtime instance identities separate.
ALTER TABLE extension_plugin_releases
    ADD CONSTRAINT extension_plugin_releases_id_plugin_uq UNIQUE (release_id, plugin_id);

CREATE TABLE extension_plugin_instances (
    instance_id UUID PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    instance_key VARCHAR(128) NOT NULL,
    release_id BIGINT,
    compatibility_mode VARCHAR(16) NOT NULL CHECK (compatibility_mode IN ('LEGACY_V1','CAPABILITY_V2')),
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN
        ('CONFIGURED','INSTALLED','ENABLED','DRAINING','DISABLED','UNHEALTHY','FAILED','UNINSTALLED')),
    configuration_generation INTEGER NOT NULL DEFAULT 0 CHECK (configuration_generation >= 0),
    public_configuration JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(public_configuration) = 'object'),
    encrypted_secrets JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(encrypted_secrets) = 'object'),
    external_account_fingerprint CHAR(64),
    runtime_identity VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plugin_id, instance_key),
    UNIQUE (instance_id, plugin_id),
    UNIQUE (runtime_identity),
    FOREIGN KEY (release_id, plugin_id) REFERENCES extension_plugin_releases(release_id, plugin_id),
    CHECK (instance_key ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    CHECK (external_account_fingerprint IS NULL OR external_account_fingerprint ~ '^[a-f0-9]{64}$')
);
CREATE INDEX extension_plugin_instances_runtime_idx ON extension_plugin_instances(runtime_identity);
CREATE INDEX extension_plugin_instances_lifecycle_idx ON extension_plugin_instances(lifecycle_state, plugin_id);

-- Durable, Core-owned plugin installation jobs. Development keeps one consolidated V1 schema snapshot.
CREATE TABLE extension_installation_jobs (
    job_id UUID PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    instance_id UUID,
    runtime_identity VARCHAR(256) NOT NULL,
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('REGISTERED','MARKETPLACE','CUSTOM','ARCHIVE')),
    operation_kind VARCHAR(16) NOT NULL DEFAULT 'INSTALL' CHECK (operation_kind IN ('INSTALL','UPDATE','ROLLBACK')),
    source_release_id BIGINT,
    previous_release_id BIGINT,
    previous_manifest_snapshot JSONB,
    previous_lifecycle_state VARCHAR(16),
    configuration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(configuration_snapshot)='object'),
    release_digest CHAR(64),
    requested_by UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    manifest_snapshot JSONB NOT NULL CHECK (jsonb_typeof(manifest_snapshot)='object'),
    permission_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(permission_snapshot)='object'),
    image_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(image_snapshot)='array'),
    state VARCHAR(32) NOT NULL CHECK (state IN ('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS',
        'REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS','INSTALLED','CONFIGURATION_REQUIRED','READY','FAILED','CANCELLED','SECURITY_BLOCKED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    FOREIGN KEY (instance_id,plugin_id) REFERENCES extension_plugin_instances(instance_id,plugin_id),
    FOREIGN KEY (source_release_id,plugin_id) REFERENCES extension_plugin_releases(release_id,plugin_id),
    FOREIGN KEY (previous_release_id,plugin_id) REFERENCES extension_plugin_releases(release_id,plugin_id),
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK (release_digest IS NULL OR release_digest ~ '^[a-f0-9]{64}$'),
    CHECK ((operation_kind='INSTALL' AND previous_release_id IS NULL AND previous_manifest_snapshot IS NULL AND previous_lifecycle_state IS NULL)
        OR (operation_kind IN ('UPDATE','ROLLBACK') AND instance_id IS NOT NULL AND previous_release_id IS NOT NULL
            AND jsonb_typeof(previous_manifest_snapshot)='object'
            AND previous_lifecycle_state IN ('ENABLED','DRAINING','DISABLED','INSTALLED'))),
    UNIQUE (requested_by,idempotency_key)
);
CREATE INDEX extension_installation_jobs_queue_idx ON extension_installation_jobs(state,created_at)
    WHERE state IN ('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS');
CREATE INDEX extension_installation_jobs_plugin_idx ON extension_installation_jobs(plugin_id,created_at DESC);
CREATE TABLE extension_local_installation_archives (
    job_id UUID PRIMARY KEY REFERENCES extension_installation_jobs(job_id) ON DELETE CASCADE,
    quarantine_name VARCHAR(64) NOT NULL UNIQUE CHECK (quarantine_name ~ '^[a-f0-9-]{36}\.oci\.tar$'),
    archive_sha256 CHAR(64) NOT NULL CHECK (archive_sha256 ~ '^sha256:[a-f0-9]{64}$'),
    archive_bytes BIGINT NOT NULL CHECK (archive_bytes > 0),
    verified_inventory JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(verified_inventory)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX extension_installation_jobs_one_active_plugin_idx ON extension_installation_jobs(plugin_id)
    WHERE instance_id IS NULL AND state IN ('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS');
CREATE UNIQUE INDEX extension_installation_jobs_one_active_instance_idx ON extension_installation_jobs(plugin_id,instance_id)
    WHERE instance_id IS NOT NULL AND state IN ('REQUESTED','POLICY_CHECK','VERIFYING_SOURCE','DOWNLOADING_OR_IMPORTING','VERIFYING_ARTIFACTS','REGISTERING_RELEASE','WAITING_FOR_OBLIGATIONS');

-- Private-registry credentials are operator-approved and encrypted with the active Core key.
CREATE TABLE extension_registry_credentials (
    registry_host VARCHAR(255) PRIMARY KEY,
    username VARCHAR(256) NOT NULL,
    encrypted_password TEXT NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    updated_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (registry_host ~ '^[a-zA-Z0-9][a-zA-Z0-9.-]*(?::[0-9]{1,5})?$'),
    CHECK (username <> ''),
    CHECK (encrypted_password <> '')
);

-- The current development schema keeps only this V1 snapshot. Signed Marketplace
-- support/security state and permanent exact OCI digest bans are Core-owned.
CREATE TABLE extension_marketplace_advisory_state (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    sequence BIGINT NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    payload_sha256 CHAR(64),
    issued_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    last_attempt_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CHECK (payload_sha256 IS NULL OR payload_sha256 ~ '^[a-f0-9]{64}$'),
    CHECK ((sequence=0 AND payload_sha256 IS NULL) OR (sequence>0 AND payload_sha256 IS NOT NULL))
);
INSERT INTO extension_marketplace_advisory_state(singleton_id) VALUES (TRUE);

CREATE TABLE extension_marketplace_release_advisories (
    plugin_id VARCHAR(160) NOT NULL,
    version VARCHAR(80) NOT NULL,
    advisory_id UUID NOT NULL,
    release_descriptor_digest VARCHAR(71) NOT NULL CHECK (release_descriptor_digest ~ '^sha256:[a-f0-9]{64}$'),
    image_digests JSONB NOT NULL CHECK (jsonb_typeof(image_digests)='array'),
    banned_image_digests JSONB NOT NULL CHECK (jsonb_typeof(banned_image_digests)='array'),
    support_status VARCHAR(16) NOT NULL CHECK (support_status IN ('SUPPORTED','UNSUPPORTED','REMOVED')),
    security_status VARCHAR(16) NOT NULL CHECK (security_status IN ('ALLOWED','BANNED')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUPPORTED','UNSUPPORTED','REMOVED','BANNED')),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
    reason_code VARCHAR(48) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    explanations JSONB NOT NULL CHECK (jsonb_typeof(explanations)='object'),
    advisory_issued_at TIMESTAMPTZ NOT NULL,
    feed_sequence BIGINT NOT NULL CHECK (feed_sequence > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_id,version),
    CHECK (status = CASE WHEN security_status='BANNED' THEN 'BANNED' ELSE support_status END)
);
CREATE INDEX extension_marketplace_advisory_status_idx
    ON extension_marketplace_release_advisories(security_status,support_status,plugin_id);

CREATE TABLE extension_marketplace_banned_artifacts (
    image_digest VARCHAR(71) PRIMARY KEY CHECK (image_digest ~ '^sha256:[a-f0-9]{64}$'),
    first_plugin_id VARCHAR(160) NOT NULL,
    first_version VARCHAR(80) NOT NULL,
    release_descriptor_digest VARCHAR(71) NOT NULL CHECK (release_descriptor_digest ~ '^sha256:[a-f0-9]{64}$'),
    reason_code VARCHAR(48) NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{0,47}$'),
    feed_sequence BIGINT NOT NULL CHECK (feed_sequence > 0),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX extension_marketplace_banned_artifacts_source_idx
    ON extension_marketplace_banned_artifacts(first_plugin_id,first_version);
CREATE FUNCTION artio_marketplace_banned_artifacts_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'extension_marketplace_banned_artifacts is append-only'; END $$;
CREATE TRIGGER extension_marketplace_banned_artifacts_immutable
    BEFORE UPDATE OR DELETE ON extension_marketplace_banned_artifacts
    FOR EACH ROW EXECUTE FUNCTION artio_marketplace_banned_artifacts_immutable();

CREATE TABLE extension_security_blocked_targets (
    runtime_identity VARCHAR(256) PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    instance_id UUID,
    banned_image_digests JSONB NOT NULL CHECK (jsonb_typeof(banned_image_digests)='array'),
    containment_status VARCHAR(16) NOT NULL CHECK (containment_status IN ('STOPPED','STOP_FAILED')),
    last_error_code VARCHAR(64),
    first_blocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at TIMESTAMPTZ,
    FOREIGN KEY (instance_id,plugin_id) REFERENCES extension_plugin_instances(instance_id,plugin_id),
    CHECK ((containment_status='STOPPED' AND last_error_code IS NULL AND stopped_at IS NOT NULL)
        OR (containment_status='STOP_FAILED' AND last_error_code IS NOT NULL AND stopped_at IS NULL))
);
CREATE INDEX extension_security_blocked_targets_status_idx
    ON extension_security_blocked_targets(containment_status,last_attempt_at);

CREATE TABLE extension_marketplace_advisory_sync_audit (
    sync_id UUID PRIMARY KEY,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('APPLIED','UNCHANGED','REJECTED','UNAVAILABLE')),
    feed_sequence BIGINT,
    payload_sha256 CHAR(64),
    error_code VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (payload_sha256 IS NULL OR payload_sha256 ~ '^[a-f0-9]{64}$')
);
CREATE INDEX extension_marketplace_advisory_sync_audit_time_idx
    ON extension_marketplace_advisory_sync_audit(occurred_at DESC);

-- Historical provider ownership deliberately restricts removal of an instance or release while
-- payments and shipments still refer to it.
ALTER TABLE payments ADD CONSTRAINT payments_provider_instance_fk
    FOREIGN KEY (provider_instance_id) REFERENCES extension_plugin_instances(instance_id);
ALTER TABLE payments ADD CONSTRAINT payments_provider_release_fk
    FOREIGN KEY (provider_release_id) REFERENCES extension_plugin_releases(release_id);
ALTER TABLE order_shipments ADD CONSTRAINT order_shipments_provider_instance_fk
    FOREIGN KEY (provider_instance_id) REFERENCES extension_plugin_instances(instance_id);
ALTER TABLE order_shipments ADD CONSTRAINT order_shipments_provider_release_fk
    FOREIGN KEY (provider_release_id) REFERENCES extension_plugin_releases(release_id);

CREATE TABLE payment_method_references (
  reference_id UUID PRIMARY KEY,
  method_id VARCHAR(64) NOT NULL CHECK (method_id ~ '^[a-z][a-z0-9._-]{0,63}$'),
  provider_plugin_id VARCHAR(128) NOT NULL,
  provider_instance_id UUID,
  provider_release_id BIGINT,
  configuration_generation INTEGER NOT NULL CHECK (configuration_generation >= 0),
  country CHAR(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
  currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
  amount NUMERIC(19,4),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK ((provider_instance_id IS NULL AND provider_release_id IS NULL)
      OR (provider_instance_id IS NOT NULL AND provider_release_id IS NOT NULL))
);
ALTER TABLE payment_method_references ADD CONSTRAINT payment_method_references_instance_fk
    FOREIGN KEY (provider_instance_id) REFERENCES extension_plugin_instances(instance_id) ON DELETE CASCADE;
ALTER TABLE payment_method_references ADD CONSTRAINT payment_method_references_release_fk
    FOREIGN KEY (provider_release_id) REFERENCES extension_plugin_releases(release_id) ON DELETE CASCADE;
CREATE INDEX payment_method_references_expiry_idx ON payment_method_references(expires_at);

-- Merchant payment-method visibility/order is scoped to the actual provider instance and method,
-- not the display label or temporary checkout reference. Development maintains one current V1 snapshot.
CREATE TABLE payment_method_preferences (
    provider_plugin_id VARCHAR(128) NOT NULL,
    provider_instance_id UUID,
    method_id VARCHAR(64) NOT NULL CHECK (method_id ~ '^[a-z][a-z0-9._-]{0,63}$'),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL CHECK (display_order BETWEEN 0 AND 10000),
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (provider_instance_id IS NULL OR provider_instance_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    FOREIGN KEY (provider_instance_id, provider_plugin_id)
        REFERENCES extension_plugin_instances(instance_id, plugin_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX payment_method_preferences_identity_uq ON payment_method_preferences(
    provider_plugin_id, COALESCE(provider_instance_id, '00000000-0000-0000-0000-000000000000'::uuid), method_id);

-- v2 declarations are immutable release metadata; the package release row remains the common runtime identity.
CREATE TABLE extension_plugin_v2_releases (
    release_id BIGINT PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL,
    manifest JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (release_id, plugin_id) REFERENCES extension_plugin_releases(release_id, plugin_id)
);
CREATE FUNCTION artio_plugin_v2_release_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'extension_plugin_v2_releases is immutable'; END $$;
CREATE TRIGGER extension_plugin_v2_releases_immutable
    BEFORE UPDATE OR DELETE ON extension_plugin_v2_releases FOR EACH ROW EXECUTE FUNCTION artio_plugin_v2_release_immutable();

-- The Core registry remains the semantic source of truth. This table mirrors only closed-policy
-- operations whose binding overlap must also be rejected by PostgreSQL, including direct writers.
CREATE TABLE extension_exclusive_routing_rules (
    capability_id VARCHAR(128) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    PRIMARY KEY (capability_id, operation_name)
);
INSERT INTO extension_exclusive_routing_rules(capability_id, operation_name)
VALUES ('catalog.enrichment.v1', 'enrich');

CREATE FUNCTION artio_exclusive_scopes_overlap(left_scope JSONB, left_priority INTEGER,
        right_scope JSONB, right_priority INTEGER) RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT left_priority = right_priority
       AND (SELECT count(*) FROM jsonb_object_keys(left_scope))
           = (SELECT count(*) FROM jsonb_object_keys(right_scope))
       AND NOT EXISTS (
           SELECT 1 FROM jsonb_each(left_scope) left_entry
           JOIN jsonb_each(right_scope) right_entry USING (key)
           WHERE left_entry.value <> right_entry.value
       )
$$;

CREATE FUNCTION artio_guard_exclusive_capability_binding() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.eligibility_state <> 'ACTIVE' OR NOT EXISTS (
        SELECT 1 FROM extension_exclusive_routing_rules
        WHERE capability_id=NEW.capability_id AND operation_name=NEW.operation_name
    ) THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext(NEW.capability_id), hashtext(NEW.operation_name));
    IF EXISTS (
        SELECT 1 FROM extension_capability_bindings existing
        WHERE existing.capability_id=NEW.capability_id
          AND existing.operation_name=NEW.operation_name
          AND existing.eligibility_state='ACTIVE'
          AND existing.binding_id IS DISTINCT FROM NEW.binding_id
          AND artio_exclusive_scopes_overlap(NEW.scope, NEW.priority, existing.scope, existing.priority)
    ) THEN
        RAISE EXCEPTION 'An equal-priority exclusive capability binding overlaps an active route'
            USING ERRCODE='23505', CONSTRAINT='extension_capability_bindings_exclusive_overlap';
    END IF;
    RETURN NEW;
END $$;

CREATE TABLE extension_capability_bindings (
    binding_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES extension_plugin_instances(instance_id),
    capability_id VARCHAR(128) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    scope JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(scope) = 'object'),
    priority INTEGER NOT NULL DEFAULT 0 CHECK (priority BETWEEN -10000 AND 10000),
    eligibility_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (eligibility_state IN ('ACTIVE','DRAINING','DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (instance_id, capability_id, operation_name, scope)
);
CREATE INDEX extension_capability_bindings_lookup_idx
    ON extension_capability_bindings(capability_id, operation_name, eligibility_state, priority DESC);
CREATE TRIGGER extension_capability_bindings_exclusive_guard
    BEFORE INSERT OR UPDATE OF capability_id, operation_name, scope, priority, eligibility_state
    ON extension_capability_bindings FOR EACH ROW EXECUTE FUNCTION artio_guard_exclusive_capability_binding();

-- Durable, frozen routing decisions and mutable attempt progress live together so a retry cannot
-- silently resolve to a different provider after a release, config, binding, or health change.
CREATE TABLE extension_operation_attempts (
    attempt_id UUID PRIMARY KEY,
    store_id UUID,
    binding_id UUID REFERENCES extension_capability_bindings(binding_id),
    instance_id UUID NOT NULL REFERENCES extension_plugin_instances(instance_id),
    plugin_id VARCHAR(128) NOT NULL,
    release_id BIGINT,
    configuration_generation INTEGER NOT NULL CHECK (configuration_generation >= 0),
    external_account_fingerprint CHAR(64),
    capability_id VARCHAR(128) NOT NULL,
    operation_name VARCHAR(64) NOT NULL,
    scope_snapshot JSONB NOT NULL CHECK (jsonb_typeof(scope_snapshot) = 'object'),
    permissions_snapshot JSONB NOT NULL CHECK (jsonb_typeof(permissions_snapshot) = 'array'),
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[a-f0-9]{64}$'),
    request_snapshot TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    deadline_at TIMESTAMPTZ NOT NULL,
    plan_snapshot JSONB NOT NULL CHECK (jsonb_typeof(plan_snapshot) = 'object'),
    attempt_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (attempt_state IN
        ('PENDING','IN_PROGRESS','SUCCEEDED','FAILED','UNKNOWN','RECONCILING')),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    result_snapshot JSONB,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (external_account_fingerprint IS NULL OR external_account_fingerprint ~ '^[a-f0-9]{64}$'),
    UNIQUE (capability_id, operation_name, idempotency_key),
    FOREIGN KEY (instance_id, plugin_id) REFERENCES extension_plugin_instances(instance_id, plugin_id),
    FOREIGN KEY (release_id, plugin_id) REFERENCES extension_plugin_releases(release_id, plugin_id)
);
CREATE INDEX extension_operation_attempts_reconcile_idx ON extension_operation_attempts(attempt_state, deadline_at)
    WHERE attempt_state IN ('PENDING','UNKNOWN','RECONCILING');
CREATE INDEX extension_operation_attempts_instance_idx ON extension_operation_attempts(instance_id, created_at DESC);

CREATE TABLE catalog_media_storage_objects (
    storage_key VARCHAR(80) PRIMARY KEY,
    content_checksum CHAR(64) NOT NULL CHECK (content_checksum ~ '^[a-f0-9]{64}$'),
    byte_length BIGINT NOT NULL CHECK (byte_length BETWEEN 1 AND 10485760),
    primary_instance_id UUID NOT NULL REFERENCES extension_plugin_instances(instance_id),
    primary_release_id BIGINT REFERENCES extension_plugin_releases(release_id),
    primary_configuration_generation INTEGER NOT NULL CHECK (primary_configuration_generation >= 0),
    primary_account_fingerprint CHAR(64) CHECK (primary_account_fingerprint IS NULL OR primary_account_fingerprint ~ '^[a-f0-9]{64}$'),
    replica_instance_ids JSONB NOT NULL CHECK (jsonb_typeof(replica_instance_ids) = 'array'),
    provider_versions JSONB NOT NULL CHECK (jsonb_typeof(provider_versions) = 'object'),
    object_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (object_state IN ('ACTIVE','DELETE_PENDING','TOMBSTONED')),
    tombstone_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((object_state = 'ACTIVE') = (tombstone_id IS NULL))
);
CREATE INDEX catalog_media_storage_objects_cleanup_idx ON catalog_media_storage_objects(object_state, updated_at)
    WHERE object_state = 'DELETE_PENDING';

-- Durable event delivery has no plugin broker credentials; a Core worker owns claim/ack/retry.
CREATE TABLE extension_event_subscriptions (
    subscription_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES extension_plugin_instances(instance_id),
    event_type VARCHAR(160) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    permissions_snapshot JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(permissions_snapshot) = 'array'),
    subscription_state VARCHAR(16) NOT NULL DEFAULT 'PENDING_APPROVAL'
        CHECK (subscription_state IN ('PENDING_APPROVAL','ACTIVE','PAUSED','DRAINING','DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (instance_id, event_type, schema_version),
    UNIQUE (subscription_id, instance_id)
);
CREATE TABLE extension_event_deliveries (
    delivery_id UUID PRIMARY KEY,
    delivery_generation INTEGER NOT NULL DEFAULT 0 CHECK (delivery_generation >= 0),
    backfill_run_id UUID,
    inbox_consumer_name VARCHAR(120) NOT NULL,
    inbox_event_id UUID NOT NULL,
    subscription_id UUID NOT NULL REFERENCES extension_event_subscriptions(subscription_id),
    instance_id UUID NOT NULL REFERENCES extension_plugin_instances(instance_id),
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    runtime_identity VARCHAR(256) NOT NULL,
    release_id BIGINT,
    configuration_generation INTEGER NOT NULL CHECK (configuration_generation >= 0),
    runtime_credential_generation INTEGER NOT NULL CHECK (runtime_credential_generation > 0),
    public_configuration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(public_configuration_snapshot) = 'object'),
    encrypted_secrets_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(encrypted_secrets_snapshot) = 'object'),
    event_type VARCHAR(160) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload_snapshot JSONB NOT NULL CHECK (jsonb_typeof(payload_snapshot) = 'object'),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','IN_PROGRESS','DELIVERED','RETRY','DEAD_LETTER','SECURITY_BLOCKED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (inbox_consumer_name, inbox_event_id, subscription_id, delivery_generation),
    FOREIGN KEY (inbox_consumer_name, inbox_event_id) REFERENCES event_inbox(consumer_name, event_id),
    FOREIGN KEY (subscription_id, instance_id) REFERENCES extension_event_subscriptions(subscription_id, instance_id),
    FOREIGN KEY (instance_id, plugin_id) REFERENCES extension_plugin_instances(instance_id, plugin_id),
    FOREIGN KEY (release_id, plugin_id) REFERENCES extension_plugin_releases(release_id, plugin_id)
);
CREATE INDEX extension_event_deliveries_claim_idx ON extension_event_deliveries(state, available_at, created_at)
    WHERE state IN ('PENDING','RETRY');
CREATE INDEX extension_event_deliveries_expired_lease_idx ON extension_event_deliveries(lease_until, created_at)
    WHERE state='IN_PROGRESS';
CREATE INDEX extension_event_deliveries_instance_idx ON extension_event_deliveries(instance_id, state, created_at DESC);
CREATE INDEX event_outbox_backfill_idx ON event_outbox(event_type, created_at, id) WHERE status='PUBLISHED';
CREATE TABLE extension_event_backfill_runs (
    run_id UUID PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    subscription_id UUID NOT NULL REFERENCES extension_event_subscriptions(subscription_id),
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL,
    max_events INTEGER NOT NULL CHECK (max_events BETWEEN 1 AND 500),
    rate_per_minute INTEGER NOT NULL CHECK (rate_per_minute BETWEEN 1 AND 60),
    event_ids JSONB NOT NULL CHECK (jsonb_typeof(event_ids)='array'),
    status VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED'
        CHECK (status IN ('PREVIEWED','QUEUED','COMPLETE','PARTIAL_FAILURE','EXPIRED')),
    requested_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    executed_at TIMESTAMPTZ,
    CHECK (from_at < to_at)
);
ALTER TABLE extension_event_deliveries ADD CONSTRAINT extension_event_deliveries_backfill_run_fk
    FOREIGN KEY (backfill_run_id) REFERENCES extension_event_backfill_runs(run_id);
CREATE INDEX extension_event_backfill_runs_plugin_idx ON extension_event_backfill_runs(plugin_id, created_at DESC);

COMMENT ON TABLE extension_plugin_instances IS 'v5.1 runtime identity; v1 registrations use an explicit legacy compatibility instance';
COMMENT ON TABLE extension_capability_bindings IS 'Core-authorized scoped capability-to-instance bindings';
COMMENT ON TABLE extension_operation_attempts IS 'Frozen plugin routing decisions with idempotency, lease fencing, and reconciliation state';
COMMENT ON TABLE extension_event_deliveries IS 'Per-instance durable event fan-out deliveries owned and dispatched by Core';

CREATE FUNCTION extension_operation_plan_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.store_id,OLD.binding_id,OLD.instance_id,OLD.plugin_id,OLD.release_id,OLD.configuration_generation,
           OLD.external_account_fingerprint,OLD.capability_id,OLD.operation_name,OLD.scope_snapshot,OLD.permissions_snapshot,
           OLD.idempotency_key,OLD.request_fingerprint,OLD.request_snapshot,OLD.correlation_id,OLD.deadline_at,OLD.plan_snapshot)
       IS DISTINCT FROM
       ROW(NEW.store_id,NEW.binding_id,NEW.instance_id,NEW.plugin_id,NEW.release_id,NEW.configuration_generation,
           NEW.external_account_fingerprint,NEW.capability_id,NEW.operation_name,NEW.scope_snapshot,NEW.permissions_snapshot,
           NEW.idempotency_key,NEW.request_fingerprint,NEW.request_snapshot,NEW.correlation_id,NEW.deadline_at,NEW.plan_snapshot) THEN
        RAISE EXCEPTION 'extension operation routing plans are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER extension_operation_plan_immutable BEFORE UPDATE ON extension_operation_attempts
    FOR EACH ROW EXECUTE FUNCTION extension_operation_plan_is_immutable();

CREATE FUNCTION extension_event_delivery_snapshot_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.delivery_generation,OLD.backfill_run_id,OLD.inbox_consumer_name,OLD.inbox_event_id,OLD.subscription_id,
           OLD.instance_id,OLD.plugin_id,OLD.runtime_identity,OLD.release_id,
           OLD.configuration_generation,OLD.runtime_credential_generation,OLD.public_configuration_snapshot,OLD.encrypted_secrets_snapshot,
           OLD.event_type,OLD.schema_version,OLD.payload_snapshot)
       IS DISTINCT FROM
       ROW(NEW.delivery_generation,NEW.backfill_run_id,NEW.inbox_consumer_name,NEW.inbox_event_id,NEW.subscription_id,
           NEW.instance_id,NEW.plugin_id,NEW.runtime_identity,NEW.release_id,
           NEW.configuration_generation,NEW.runtime_credential_generation,NEW.public_configuration_snapshot,NEW.encrypted_secrets_snapshot,
           NEW.event_type,NEW.schema_version,NEW.payload_snapshot) THEN
        RAISE EXCEPTION 'extension event delivery snapshots are immutable';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER extension_event_delivery_snapshot_immutable BEFORE UPDATE ON extension_event_deliveries
    FOR EACH ROW EXECUTE FUNCTION extension_event_delivery_snapshot_is_immutable();

CREATE FUNCTION extension_event_backfill_plan_is_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.plugin_id,OLD.subscription_id,OLD.from_at,OLD.to_at,OLD.max_events,OLD.rate_per_minute,
           OLD.event_ids,OLD.requested_by,OLD.created_at,OLD.expires_at)
       IS DISTINCT FROM
       ROW(NEW.plugin_id,NEW.subscription_id,NEW.from_at,NEW.to_at,NEW.max_events,NEW.rate_per_minute,
           NEW.event_ids,NEW.requested_by,NEW.created_at,NEW.expires_at) THEN
        RAISE EXCEPTION 'extension event backfill preview is immutable';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
            (OLD.status='PREVIEWED' AND NEW.status IN ('QUEUED','EXPIRED'))
            OR (OLD.status='QUEUED' AND NEW.status IN ('COMPLETE','PARTIAL_FAILURE'))
            OR (OLD.status='PARTIAL_FAILURE' AND NEW.status IN ('QUEUED','COMPLETE'))) THEN
        RAISE EXCEPTION 'extension event backfill status transition is invalid';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER extension_event_backfill_plan_immutable BEFORE UPDATE ON extension_event_backfill_runs
    FOR EACH ROW EXECUTE FUNCTION extension_event_backfill_plan_is_immutable();

-- Current development schema additions are kept in this single V1 snapshot.
ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE artio_password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX artio_password_reset_tokens_user_id_idx ON artio_password_reset_tokens(user_id);
CREATE INDEX artio_password_reset_tokens_expiry_idx ON artio_password_reset_tokens(expires_at) WHERE used_at IS NULL;
