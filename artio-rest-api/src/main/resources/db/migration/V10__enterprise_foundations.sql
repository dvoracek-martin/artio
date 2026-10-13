CREATE TABLE tenants (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  domain VARCHAR(255) NOT NULL UNIQUE,
  plan VARCHAR(24) NOT NULL DEFAULT 'STARTER' CHECK (plan IN ('FREE','STARTER','BUSINESS','ENTERPRISE')),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  default_currency CHAR(3) NOT NULL DEFAULT 'USD',
  default_locale VARCHAR(16) NOT NULL DEFAULT 'en',
  settings JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO tenants(id,name,domain,plan) VALUES ('00000000-0000-0000-0000-000000000001','Artio Default','default.artio.local','ENTERPRISE');
ALTER TABLE users ADD COLUMN tenant_id UUID REFERENCES tenants(id);
UPDATE users SET tenant_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE users ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE TABLE tenant_memberships (
  tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,user_id)
);
INSERT INTO tenant_memberships(tenant_id,user_id,role) SELECT tenant_id,id,role FROM users;

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
  tenant_id UUID REFERENCES tenants(id),
  user_id UUID,
  action VARCHAR(16) NOT NULL,
  path VARCHAR(512) NOT NULL,
  status INTEGER NOT NULL,
  ip_address VARCHAR(64),
  user_agent VARCHAR(512),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant_time ON audit_logs(tenant_id,occurred_at DESC);
CREATE INDEX idx_audit_actor_time ON audit_logs(user_id,occurred_at DESC);
CREATE FUNCTION artio_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'audit_logs is append-only'; END $$;
CREATE TRIGGER audit_logs_immutable BEFORE UPDATE OR DELETE ON audit_logs FOR EACH ROW EXECUTE FUNCTION artio_audit_immutable();

CREATE TABLE consent_records (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  essential BOOLEAN NOT NULL DEFAULT TRUE CHECK (essential),
  analytics BOOLEAN NOT NULL DEFAULT FALSE,
  marketing BOOLEAN NOT NULL DEFAULT FALSE,
  policy_version VARCHAR(32) NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_user ON consent_records(tenant_id,user_id,recorded_at DESC);
CREATE TABLE gift_cards (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
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
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  points BIGINT NOT NULL DEFAULT 0 CHECK (points >= 0),
  tier VARCHAR(16) NOT NULL DEFAULT 'BRONZE',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(tenant_id,user_id)
);
CREATE TABLE loyalty_transactions (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  order_id UUID,
  points BIGINT NOT NULL CHECK (points <> 0),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('EARN','REDEEM','ADJUST')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,user_id,order_id,kind)
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
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  variant_id UUID NOT NULL,
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('VIEW','CART','PURCHASE')),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_interactions_recommendations ON product_interactions(tenant_id,kind,occurred_at DESC);
ALTER TABLE product_interactions ADD COLUMN order_id UUID;
CREATE UNIQUE INDEX uq_purchase_interaction_order ON product_interactions(tenant_id,user_id,order_id,variant_id) WHERE kind='PURCHASE' AND order_id IS NOT NULL;
CREATE TABLE fraud_reviews (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  order_id UUID NOT NULL,
  score INTEGER NOT NULL CHECK (score BETWEEN 0 AND 100),
  signals JSONB NOT NULL,
  decision VARCHAR(16) NOT NULL CHECK (decision IN ('ALLOW','REVIEW','HOLD')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(tenant_id,order_id)
);
CREATE TABLE subscriptions (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  frequency VARCHAR(16) NOT NULL CHECK (frequency IN ('WEEKLY','MONTHLY','QUARTERLY')),
  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
  items JSONB NOT NULL,
  next_billing_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_due ON subscriptions(next_billing_at) WHERE status='ACTIVE';
