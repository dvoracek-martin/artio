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
