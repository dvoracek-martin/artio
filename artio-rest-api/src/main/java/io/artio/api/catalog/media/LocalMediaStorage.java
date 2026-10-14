package io.artio.api.catalog.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Filesystem adapter intended for a persistent mounted volume, never the container writable layer. */
@Component
public class LocalMediaStorage implements MediaStorage {
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)");
    private final Path root;

    public LocalMediaStorage(@Value("${artio.catalog.media.storage-path:./var/artio/media}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, byte[] content) {
        Path destination = resolve(key);
        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".artio-media-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Catalog media could not be stored", failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    @Override
    public byte[] read(String key) {
        try { return Files.readAllBytes(resolve(key)); }
        catch (IOException failure) { throw new IllegalStateException("Catalog media could not be read", failure); }
    }

    @Override
    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException failure) { throw new IllegalStateException("Catalog media could not be removed", failure); }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) throw new IllegalArgumentException("Invalid catalog media key");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid catalog media key");
        return target;
    }
}
