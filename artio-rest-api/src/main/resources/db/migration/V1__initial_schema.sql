-- Artio Core v1 clean-install baseline. Consolidates the pre-release V1-V27 schema.
-- The project owner confirmed that no external database data requires upgrade compatibility.
-- Source migration names are retained as section comments to preserve schema history.

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

-- ===== Legacy V2__catalog_taxonomy_and_attributes =====
CREATE TABLE catalog_category (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
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

CREATE TABLE inventory_reservation (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    warehouse_id UUID NOT NULL REFERENCES inventory_warehouse(id),
    sellable_id UUID NOT NULL,
    quantity NUMERIC(19, 4) NOT NULL CHECK (quantity > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('RESERVED', 'ALLOCATED', 'RELEASED')),
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
CREATE TABLE carts (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL);
CREATE TABLE cart_items (cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE, variant_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price NUMERIC(19,4) NOT NULL CHECK (unit_price >= 0), currency CHAR(3) NOT NULL, PRIMARY KEY (cart_id, variant_id));
CREATE TABLE orders (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), status VARCHAR(32) NOT NULL, subtotal NUMERIC(19,4) NOT NULL, tax NUMERIC(19,4) NOT NULL, shipping NUMERIC(19,4) NOT NULL, total NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL, shipping_address TEXT NOT NULL, billing_address TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, shipping_method_id VARCHAR(120), shipping_parcel_weight_kg NUMERIC(12,3), shipment_reference VARCHAR(255), shipping_label_url TEXT);
CREATE TABLE order_items (id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE, variant_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL);
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
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_carts_user ON carts(user_id);


-- ===== Legacy V7__payments_notifications =====
CREATE TABLE payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  user_id UUID NOT NULL REFERENCES users(id),
  amount NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
  refunded_amount NUMERIC(19,4) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
  currency CHAR(3) NOT NULL,
  method VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  provider_reference VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_payments_provider_reference ON payments(provider_reference);
CREATE INDEX idx_payments_order ON payments(order_id);
CREATE TABLE payment_refunds (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments(id),
  idempotency_key VARCHAR(128) NOT NULL,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
  provider_reference VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(payment_id,idempotency_key)
);
CREATE INDEX idx_payment_refunds_payment ON payment_refunds(payment_id,created_at);
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
CREATE TABLE gift_card_transactions (
  id UUID PRIMARY KEY,
  gift_card_id UUID NOT NULL REFERENCES gift_cards(id),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  order_id UUID,
  amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('REDEEM','REFUND')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_gift_card_redemption_per_order ON gift_card_transactions(gift_card_id,order_id) WHERE order_id IS NOT NULL AND kind='REDEEM';

CREATE TABLE loyalty_accounts (
  user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  points BIGINT NOT NULL DEFAULT 0 CHECK (points >= 0),
  tier VARCHAR(16) NOT NULL DEFAULT 'BRONZE',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE loyalty_transactions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  order_id UUID,
  points BIGINT NOT NULL CHECK (points <> 0),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('EARN','REDEEM','ADJUST')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(user_id,order_id,kind)
);

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
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, locale)
);

CREATE INDEX catalog_product_translations_locale_idx ON catalog_product_translations(locale, product_id);


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
    action VARCHAR(32) NOT NULL,
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
    WHERE method = 'STRIPE' AND status = 'PENDING';


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
