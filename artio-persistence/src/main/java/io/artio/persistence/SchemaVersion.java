package io.artio.persistence;

import java.util.Objects;

/** Identifies a version of a persistence schema. */
public record SchemaVersion(String value) {
    public SchemaVersion {
        Objects.requireNonNull(value, "Schema version is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Schema version must not be blank");
        }
    }
}