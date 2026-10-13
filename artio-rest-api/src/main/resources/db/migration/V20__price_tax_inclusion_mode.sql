ALTER TABLE pricing_price
    ADD COLUMN prices_include_tax BOOLEAN NOT NULL DEFAULT FALSE;
