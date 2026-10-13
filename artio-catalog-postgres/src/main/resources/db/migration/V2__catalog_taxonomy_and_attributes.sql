CREATE TABLE catalog_category (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    parent_id UUID REFERENCES catalog_category(id),
    active BOOLEAN NOT NULL
);
CREATE INDEX catalog_category_parent_id_idx ON catalog_category(parent_id);

CREATE TABLE catalog_brand (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    description VARCHAR(2000),
    active BOOLEAN NOT NULL
);

CREATE TABLE catalog_attribute_definition (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(200) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'BOOLEAN')),
    required BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL
);

ALTER TABLE catalog_product ADD COLUMN brand_id UUID REFERENCES catalog_brand(id);
CREATE INDEX catalog_product_brand_id_idx ON catalog_product(brand_id);

CREATE TABLE catalog_product_category (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES catalog_category(id),
    PRIMARY KEY (product_id, category_id)
);
CREATE INDEX catalog_product_category_category_id_idx ON catalog_product_category(category_id);

CREATE TABLE catalog_product_attribute (
    product_id UUID NOT NULL REFERENCES catalog_product(id) ON DELETE CASCADE,
    attribute_code VARCHAR(64) NOT NULL REFERENCES catalog_attribute_definition(code),
    value VARCHAR(2000) NOT NULL,
    PRIMARY KEY (product_id, attribute_code)
);
