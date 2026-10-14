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
