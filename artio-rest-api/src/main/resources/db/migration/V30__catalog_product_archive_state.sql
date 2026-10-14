ALTER TABLE catalog_product
    ADD COLUMN archived_at TIMESTAMPTZ;

CREATE INDEX catalog_product_archived_idx
    ON catalog_product (archived_at, updated_at DESC)
    WHERE archived_at IS NOT NULL;
