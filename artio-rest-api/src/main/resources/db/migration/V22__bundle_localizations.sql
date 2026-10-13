CREATE TABLE commerce_bundle_translations (
    bundle_id UUID NOT NULL REFERENCES commerce_bundle(id) ON DELETE CASCADE,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de', 'it', 'fr', 'en')),
    name VARCHAR(500) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bundle_id, locale)
);

CREATE TABLE commerce_bundle_option_group_translations (
    bundle_id UUID NOT NULL,
    group_code VARCHAR(64) NOT NULL,
    locale VARCHAR(2) NOT NULL CHECK (locale IN ('de', 'it', 'fr', 'en')),
    name VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (bundle_id, group_code, locale),
    FOREIGN KEY (bundle_id, group_code) REFERENCES commerce_bundle_option_group(bundle_id, code) ON DELETE CASCADE
);
