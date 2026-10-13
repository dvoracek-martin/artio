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
