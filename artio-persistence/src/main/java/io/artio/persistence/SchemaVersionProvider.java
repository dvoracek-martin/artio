package io.artio.persistence;

/** Provides the schema version understood by a persistence adapter. */
@FunctionalInterface
public interface SchemaVersionProvider {
    SchemaVersion currentVersion();
}