CREATE TABLE commerce_bundle_market_price (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    market CHAR(2) NOT NULL CHECK (market = upper(market) AND market ~ '^[A-Z]{2}$'),
    fixed_amount NUMERIC(19, 4) NOT NULL CHECK (fixed_amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = upper(currency) AND currency ~ '^[A-Z]{3}$'),
    PRIMARY KEY (bundle_id, market)
);
