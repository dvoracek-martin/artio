package io.artio.api.extensions;

import io.artio.extensions.PluginManifest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Central local policy for exact image digest bans received from signed Marketplace advisories. */
public final class ExtensionSecurityPolicy {
  private ExtensionSecurityPolicy() {}

  public static List<String> bannedDigests(JdbcTemplate jdbc, PluginManifest manifest) {
    List<String> imageDigests = imageDigests(manifest);
    if (imageDigests.isEmpty()) return List.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(imageDigests.size(), "?"));
    return jdbc.query(
        "SELECT image_digest FROM extension_marketplace_banned_artifacts WHERE image_digest IN ("
            + placeholders
            + ") ORDER BY image_digest",
        (rs, row) -> rs.getString(1),
        imageDigests.toArray());
  }

  public static void requireAllowed(JdbcTemplate jdbc, PluginManifest manifest) {
    List<String> banned = bannedDigests(jdbc, manifest);
    if (!banned.isEmpty()) throw new BannedArtifactException(banned);
  }

  public static List<String> imageDigests(PluginManifest manifest) {
    if (manifest == null) return List.of();
    List<String> images = new ArrayList<>();
    addDigest(images, manifest.image());
    if (manifest.services() != null) manifest.services().forEach(service -> addDigest(images, service.image()));
    return images.stream().distinct().sorted().toList();
  }

  private static void addDigest(List<String> target, String image) {
    if (image == null) return;
    int delimiter = image.lastIndexOf("@sha256:");
    if (delimiter < 1) return;
    String digest = image.substring(delimiter + 1);
    if (digest.matches("sha256:[a-f0-9]{64}")) target.add(digest);
  }

  public static final class BannedArtifactException extends ResponseStatusException {
    private final List<String> imageDigests;

    public BannedArtifactException(List<String> imageDigests) {
      super(HttpStatus.LOCKED, "Extension artifact is blocked by a verified Marketplace security advisory");
      this.imageDigests = List.copyOf(imageDigests);
    }

    public List<String> imageDigests() {
      return imageDigests;
    }
  }
}
