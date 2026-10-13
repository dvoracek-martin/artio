ALTER TABLE extension_plugins
    ADD COLUMN credential_generation INTEGER NOT NULL DEFAULT 1 CHECK (credential_generation > 0),
    ADD COLUMN rotation_was_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN rotation_previous_state VARCHAR(16);

ALTER TABLE extension_plugins DROP CONSTRAINT extension_plugins_lifecycle_state_check;
ALTER TABLE extension_plugins ADD CONSTRAINT extension_plugins_lifecycle_state_check CHECK (lifecycle_state IN
    ('VALIDATED','CONFIGURED','INSTALLED','ENABLED','DISABLED','UNHEALTHY','UPDATING','ROTATING','FAILED','UNINSTALLED'));
