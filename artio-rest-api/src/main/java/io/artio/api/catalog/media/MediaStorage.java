package io.artio.api.catalog.media;

/** Durable byte storage for catalog media; implementations must treat keys as opaque identifiers. */
public interface MediaStorage {
    void store(String key, byte[] content);
    byte[] read(String key);
    void delete(String key);
}
