package io.artio.api.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Bounded disk spool for untrusted plugin archives; request bytes never accumulate in heap. */
@Service
public final class OciArchiveQuarantineStore {
  private static final int BUFFER_SIZE = 64 * 1024;
  private final Path root;
  private final long maximumBytes;

  public OciArchiveQuarantineStore(
      @Value("${artio.extensions.archive.quarantine-path:/var/lib/artio/extensions/quarantine}") String root,
      @Value("${artio.extensions.archive.maximum-bytes:2147483648}") long maximumBytes) {
    if (maximumBytes <= 0) throw new IllegalArgumentException("Maximum OCI archive size must be positive");
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.maximumBytes = maximumBytes;
  }

  public StoredArchive store(MultipartFile upload) throws IOException {
    if (upload == null || upload.isEmpty() || upload.getSize() > maximumBytes) {
      throw new IllegalArgumentException("OCI archive is empty or exceeds the configured upload limit");
    }
    Files.createDirectories(root);
    Path path = root.resolve(UUID.randomUUID() + ".oci.tar").normalize();
    if (!path.getParent().equals(root)) throw new IllegalStateException("OCI quarantine path escaped its root");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    long total = 0;
    boolean complete = false;
    try {
      try {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException unsupported) {
        Files.createFile(path);
      }
      try (InputStream input = upload.getInputStream(); var output = Files.newOutputStream(path)) {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (count == 0) continue;
          if (total > maximumBytes - count) {
            throw new IllegalArgumentException("OCI archive exceeds the configured upload limit");
          }
          output.write(buffer, 0, count);
          digest.update(buffer, 0, count);
          total += count;
        }
      }
      if (total == 0) throw new IllegalArgumentException("OCI archive is empty");
      complete = true;
      return new StoredArchive(path, total, "sha256:" + HexFormat.of().formatHex(digest.digest()));
    } finally {
      if (!complete) Files.deleteIfExists(path);
    }
  }

  public void delete(Path archive) throws IOException {
    Path normalized = archive.toAbsolutePath().normalize();
    if (!normalized.getParent().equals(root)) throw new IllegalArgumentException("Archive is outside the OCI quarantine");
    Files.deleteIfExists(normalized);
  }

  public Path pathFor(String quarantineName) {
    if (quarantineName == null || !quarantineName.matches("[a-f0-9-]{36}\\.oci\\.tar")) {
      throw new IllegalArgumentException("Invalid OCI quarantine identity");
    }
    Path path = root.resolve(quarantineName).normalize();
    if (!path.getParent().equals(root)) throw new IllegalArgumentException("Archive is outside the OCI quarantine");
    return path;
  }

  public record StoredArchive(Path path, long size, String sha256) {}
}
