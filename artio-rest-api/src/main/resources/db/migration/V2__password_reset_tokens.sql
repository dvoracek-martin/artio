ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE artio_password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX artio_password_reset_tokens_user_id_idx ON artio_password_reset_tokens(user_id);
CREATE INDEX artio_password_reset_tokens_expiry_idx ON artio_password_reset_tokens(expires_at) WHERE used_at IS NULL;
