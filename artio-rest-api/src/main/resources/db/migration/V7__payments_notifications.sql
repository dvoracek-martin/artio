CREATE TABLE payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES orders(id),
  user_id UUID NOT NULL REFERENCES users(id),
  amount NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
  currency CHAR(3) NOT NULL,
  method VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  provider_reference VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_payments_provider_reference ON payments(provider_reference);
CREATE INDEX idx_payments_order ON payments(order_id);
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
