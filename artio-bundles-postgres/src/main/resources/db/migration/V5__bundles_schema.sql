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
