CREATE TABLE extension_plugin_releases (
    release_id BIGSERIAL PRIMARY KEY,
    plugin_id VARCHAR(128) NOT NULL REFERENCES extension_plugins(plugin_id),
    version VARCHAR(64) NOT NULL,
    image_reference VARCHAR(512) NOT NULL,
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plugin_id, version)
);

INSERT INTO extension_plugin_releases(plugin_id, version, image_reference, manifest)
SELECT plugin_id, manifest->>'version', image_reference, manifest
FROM extension_plugins
WHERE lifecycle_state <> 'UNINSTALLED'
ON CONFLICT (plugin_id, version) DO NOTHING;
