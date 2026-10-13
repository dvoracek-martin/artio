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
CREATE TABLE orders (id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), status VARCHAR(32) NOT NULL, subtotal NUMERIC(19,4) NOT NULL, tax NUMERIC(19,4) NOT NULL, shipping NUMERIC(19,4) NOT NULL, total NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL, shipping_address TEXT NOT NULL, billing_address TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL);
CREATE TABLE order_items (id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE, variant_id UUID NOT NULL, quantity INTEGER NOT NULL CHECK (quantity > 0), unit_price NUMERIC(19,4) NOT NULL, currency CHAR(3) NOT NULL);
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_created ON orders(created_at DESC);
CREATE INDEX idx_carts_user ON carts(user_id);
