CREATE TABLE kafka_consumer_dead_letters (
    id UUID PRIMARY KEY,
    source_topic VARCHAR(249) NOT NULL,
    source_partition INTEGER NOT NULL CHECK (source_partition >= 0),
    source_offset BIGINT NOT NULL CHECK (source_offset >= 0),
    event_id VARCHAR(128),
    event_type VARCHAR(128),
    record_key VARCHAR(512),
    encrypted_payload TEXT,
    payload_is_null BOOLEAN NOT NULL DEFAULT FALSE,
    schema_version VARCHAR(32),
    error_class VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DEAD' CHECK (status IN ('DEAD','REPLAY_REQUESTED','REPLAYING','REPLAYED')),
    replay_attempts INTEGER NOT NULL DEFAULT 0 CHECK (replay_attempts >= 0),
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_at TIMESTAMPTZ,
    replayed_at TIMESTAMPTZ,
    last_replay_error VARCHAR(128),
    dead_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_topic, source_partition, source_offset)
);

CREATE INDEX kafka_consumer_dead_letters_status_idx
    ON kafka_consumer_dead_letters(status, available_at, dead_at DESC);
