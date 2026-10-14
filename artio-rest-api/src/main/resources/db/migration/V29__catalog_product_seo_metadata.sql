ALTER TABLE catalog_product
    ADD COLUMN seo_slug VARCHAR(160),
    ADD COLUMN seo_title VARCHAR(180),
    ADD COLUMN seo_description VARCHAR(320);

ALTER TABLE catalog_product
    ADD CONSTRAINT catalog_product_seo_slug_format CHECK (
        seo_slug IS NULL OR seo_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    );

CREATE UNIQUE INDEX catalog_product_seo_slug_unique_idx
    ON catalog_product (seo_slug)
    WHERE seo_slug IS NOT NULL;
