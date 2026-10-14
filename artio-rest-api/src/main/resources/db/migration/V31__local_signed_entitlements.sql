CREATE TABLE artio_license_state (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    installation_id UUID NOT NULL DEFAULT gen_random_uuid(),
    signed_document TEXT,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    last_refresh_attempt_at TIMESTAMPTZ,
    last_refresh_success_at TIMESTAMPTZ,
    refresh_status VARCHAR(32) NOT NULL DEFAULT 'NEVER' CHECK (refresh_status IN
        ('NEVER','IN_PROGRESS','SUCCESS','UNAVAILABLE','INVALID_RESPONSE','INVALID_CONFIGURATION')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (signed_document IS NULL OR octet_length(signed_document) <= 65536)
);

INSERT INTO artio_license_state(singleton_id) VALUES (TRUE);
