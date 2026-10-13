ALTER TABLE pricing_tax_rule
    ADD COLUMN tax_category VARCHAR(24) NOT NULL DEFAULT 'STANDARD'
        CHECK (tax_category IN ('STANDARD', 'REDUCED', 'ACCOMMODATION'));

CREATE INDEX pricing_tax_category_lookup_idx
    ON pricing_tax_rule(jurisdiction, tax_category, active, valid_from);

CREATE TABLE pricing_sellable_tax_category (
    sellable_id UUID PRIMARY KEY,
    category VARCHAR(24) NOT NULL
        CHECK (category IN ('STANDARD', 'REDUCED', 'ACCOMMODATION')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO pricing_tax_rule(id, jurisdiction, tax_category, percentage, valid_from, valid_until, active)
VALUES
    ('00000000-0000-0000-0000-000000000081', 'CH', 'STANDARD', 8.1, '2024-01-01T00:00:00Z', NULL, TRUE),
    ('00000000-0000-0000-0000-000000000026', 'CH', 'REDUCED', 2.6, '2024-01-01T00:00:00Z', NULL, TRUE),
    ('00000000-0000-0000-0000-000000000038', 'CH', 'ACCOMMODATION', 3.8, '2024-01-01T00:00:00Z', NULL, TRUE);
