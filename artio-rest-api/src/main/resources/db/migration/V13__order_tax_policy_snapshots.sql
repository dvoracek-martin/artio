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
