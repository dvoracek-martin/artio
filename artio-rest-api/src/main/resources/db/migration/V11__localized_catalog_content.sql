CREATE TABLE catalog_product_translations (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de','it','fr','en')),
    name VARCHAR(500) NOT NULL,
    description VARCHAR(5000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, locale)
);

CREATE INDEX catalog_product_translations_locale_idx ON catalog_product_translations(locale, product_id);
