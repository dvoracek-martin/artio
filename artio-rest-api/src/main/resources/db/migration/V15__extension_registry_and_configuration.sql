CREATE TABLE extension_plugins (
    plugin_id VARCHAR(128) PRIMARY KEY,
    manifest JSONB NOT NULL,
    image_reference VARCHAR(512) NOT NULL,
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN
        ('VALIDATED','CONFIGURED','INSTALLED','ENABLED','DISABLED','UNHEALTHY','UPDATING','FAILED','UNINSTALLED')),
    public_configuration JSONB NOT NULL DEFAULT '{}'::jsonb,
    encrypted_secrets JSONB NOT NULL DEFAULT '{}'::jsonb,
    configuration_version INTEGER NOT NULL DEFAULT 0 CHECK (configuration_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extension_audit_events (
    id UUID PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL,
    actor_user_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX extension_audit_plugin_time_idx ON extension_audit_events(plugin_id, occurred_at DESC);
CREATE FUNCTION artio_extension_audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'extension_audit_events is append-only'; END $$;
CREATE TRIGGER extension_audit_events_immutable BEFORE UPDATE OR DELETE ON extension_audit_events FOR EACH ROW EXECUTE FUNCTION artio_extension_audit_immutable();
