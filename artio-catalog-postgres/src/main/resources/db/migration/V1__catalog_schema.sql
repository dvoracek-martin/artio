CREATE TABLE catalog_product (
    id UUID PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    description VARCHAR(5000),
    sku VARCHAR(50) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE catalog_variant (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES catalog_product(id),
    name VARCHAR(500) NOT NULL,
    sku VARCHAR(50) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX catalog_variant_product_id_idx ON catalog_variant(product_id);