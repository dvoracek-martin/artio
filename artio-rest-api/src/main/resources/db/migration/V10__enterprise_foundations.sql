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
