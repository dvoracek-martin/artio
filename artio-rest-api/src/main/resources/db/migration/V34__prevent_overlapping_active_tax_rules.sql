CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE pricing_tax_rule
    ADD CONSTRAINT pricing_tax_rule_active_period_excl
    EXCLUDE USING gist (
        jurisdiction WITH =,
        tax_category WITH =,
        tstzrange(valid_from, valid_until, '[)') WITH &&
    ) WHERE (active);
