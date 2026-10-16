package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OciArchiveInspectorTests {
  private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String CONFIG_TYPE = "application/vnd.oci.image.config.v1+json";
  private static final String LAYER_TYPE = "application/vnd.oci.image.layer.v1.tar+gzip";
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void validatesDigestPinnedMultiImageBundleAndLayerDiffIds() throws Exception {
    Image primary = image("/usr/bin/app", "main");
    Image sidecar = image("/usr/bin/sidecar", "sidecar");
    Path archive = writeArchive(List.of(primary, sidecar), false);
    PluginManifest manifest = manifest(primary.manifestDigest(), sidecar.manifestDigest());

    var inspection = new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults())
        .inspect(archive, manifest);

    assertThat(inspection.images()).containsKeys(primary.manifestDigest(), sidecar.manifestDigest());
    assertThat(inspection.blobCount()).isEqualTo(6);
    assertThat(inspection.expandedLayerEntries()).isEqualTo(2);
    assertThat(inspection.images().get(primary.manifestDigest()).configDigests()).containsExactly(primary.configDigest());
  }

  @Test
  void validatesDockerSaveArchiveWithoutClaimingARegistryManifestDigest() throws Exception {
    byte[] layer = tar(Map.of("usr/bin/app", "docker-save-layer".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String diffId = digest(layer);
    byte[] config = JSON.writeValueAsBytes(Map.of("architecture", "amd64", "os", "linux",
        "rootfs", Map.of("type", "layers", "diff_ids", List.of(diffId))));
    String configDigest = digest(config);
    String referenceDigest = "sha256:" + "b".repeat(64);
    Path archive = writeDockerSaveArchive(config, configDigest, layer);

    var inspected = inspector().inspect(archive, manifest(referenceDigest));

    assertThat(inspected.sourceFormat()).isEqualTo("DOCKER_SAVE");
    assertThat(inspected.images()).containsKey(referenceDigest);
    assertThat(inspected.images().get(referenceDigest).manifestDigest()).isNull();
    assertThat(inspected.images().get(referenceDigest).configDigests()).containsExactly(configDigest);
  }

  @Test
  void rejectsTraversalAndSymbolicLinksInOuterArchive() throws Exception {
    Image image = image("bin/app", "main");
    Path traversal = writeArchive(List.of(image), true);

    assertThatThrownBy(() -> inspector().inspect(traversal, manifest(image.manifestDigest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe path");

    Path linked = writeArchiveWithLink(image);
    assertThatThrownBy(() -> inspector().inspect(linked, manifest(image.manifestDigest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("link or special file");
  }

  @Test
  void rejectsDigestMismatchMissingSidecarAndExcessiveLayerExpansion() throws Exception {
    Image image = image("bin/app", "main");
    Path archive = writeArchive(List.of(image), false);
    PluginManifest wrongDigest = manifest("sha256:" + "0".repeat(64));

    assertThatThrownBy(() -> inspector().inspect(archive, wrongDigest))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("do not exactly match");

    var tightLimits = new OciArchiveInspector.Limits(4L << 20, 4, 1, 100, 4, 1 << 20);
    assertThatThrownBy(() -> new OciArchiveInspector(tightLimits).inspect(archive, manifest(image.manifestDigest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expanded-size");
  }

  @Test
  void rejectsLinksAndTraversalInsideImageLayers() throws Exception {
    for (String name : List.of("../escape", "./ambiguous")) {
      byte[] layer = tar(Map.of(name, new byte[] {1}));
      Image image = imageWithLayerTar(layer, LAYER_TYPE);
      Path archive = writeArchive(List.of(image), false);
      assertThatThrownBy(() -> inspector().inspect(archive, manifest(image.manifestDigest())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("layer contains");
    }

    Image linkedImage = imageWithLayerTar(tarWithLink(), LAYER_TYPE);
    Path linkedArchive = writeArchive(List.of(linkedImage), false);
    assertThatThrownBy(() -> inspector().inspect(linkedArchive, manifest(linkedImage.manifestDigest())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("link or unsupported special file");
  }

  private OciArchiveInspector inspector() {
    return new OciArchiveInspector(OciArchiveInspector.Limits.developmentDefaults());
  }

  private PluginManifest manifest(String... digests) {
    String primary = "registry.customer.test/plugins/example@" + digests[0];
    List<PluginManifest.BundleService> services = digests.length > 1
        ? List.of(new PluginManifest.BundleService("helper", "registry.customer.test/plugins/helper@" + digests[1],
            8081, "/health", Map.of(), List.of(), List.of()))
        : List.of();
    return new PluginManifest("1", "customer.example", "Customer Plugin", "Customer", "1.0.0",
        "Locally supplied plugin", "0.1.0", "9.0.0", primary, List.of(), List.of(), Map.of(),
        List.of(), "/health", "/diagnostics", null, services, List.of(), Map.of());
  }

  private Image image(String layerPath, String label) throws Exception {
    byte[] layerTar = tar(Map.of(layerPath, ("layer-" + label).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return imageWithLayerTar(layerTar, LAYER_TYPE);
  }

  private Image imageWithLayerTar(byte[] layerTar, String layerType) throws Exception {
    byte[] gzipLayer = gzip(layerTar);
    String layerDigest = digest(gzipLayer);
    String diffId = digest(layerTar);
    byte[] config = JSON.writeValueAsBytes(Map.of(
        "architecture", "amd64", "os", "linux",
        "rootfs", Map.of("type", "layers", "diff_ids", List.of(diffId))));
    String configDigest = digest(config);
    byte[] imageManifest = JSON.writeValueAsBytes(Map.of(
        "schemaVersion", 2, "mediaType", MANIFEST_TYPE,
        "config", Map.of("mediaType", CONFIG_TYPE, "digest", configDigest, "size", config.length),
        "layers", List.of(Map.of("mediaType", layerType, "digest", layerDigest, "size", gzipLayer.length))));
    return new Image(imageManifest, digest(imageManifest), config, configDigest, gzipLayer, layerDigest);
  }

  private Path writeArchive(List<Image> images, boolean traversal) throws Exception {
    var blobs = new java.util.LinkedHashMap<String, byte[]>();
    var descriptors = new java.util.ArrayList<Map<String, Object>>();
    for (Image image : images) {
      blobs.put("blobs/sha256/" + image.manifestDigest().substring(7), image.manifest());
      blobs.put("blobs/sha256/" + image.configDigest().substring(7), image.config());
      blobs.put("blobs/sha256/" + image.layerDigest().substring(7), image.layer());
      descriptors.add(Map.of("mediaType", MANIFEST_TYPE, "digest", image.manifestDigest(), "size", image.manifest().length));
    }
    byte[] index = JSON.writeValueAsBytes(Map.of("schemaVersion", 2, "mediaType", "application/vnd.oci.image.index.v1+json", "manifests", descriptors));
    Path path = temporaryDirectory.resolve("bundle-" + images.size() + "-" + traversal + ".tar");
    try (var output = Files.newOutputStream(path); var tar = new TarArchiveOutputStream(output)) {
      add(tar, "oci-layout", "{\"imageLayoutVersion\":\"1.0.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      add(tar, "index.json", index);
      addDirectory(tar, "blobs/");
      addDirectory(tar, "blobs/sha256/");
      if (traversal) add(tar, "../escape", new byte[] {1});
      for (var blob : blobs.entrySet()) add(tar, blob.getKey(), blob.getValue());
      tar.finish();
    }
    return path;
  }

  private Path writeDockerSaveArchive(byte[] config, String configDigest, byte[] layer) throws Exception {
    String configPath = configDigest.substring("sha256:".length()) + ".json";
    byte[] dockerManifest = JSON.writeValueAsBytes(List.of(Map.of(
        "Config", configPath,
        "RepoTags", List.of("registry.customer.test/plugins/example:local"),
        "Layers", List.of("layer-id/layer.tar"))));
    Path path = temporaryDirectory.resolve("docker-save.tar");
    try (var output = Files.newOutputStream(path); var tar = new TarArchiveOutputStream(output)) {
      add(tar, "manifest.json", dockerManifest);
      add(tar, configPath, config);
      addDirectory(tar, "layer-id/");
      add(tar, "layer-id/layer.tar", layer);
      tar.finish();
    }
    return path;
  }

  private Path writeArchiveWithLink(Image image) throws Exception {
    Path path = writeArchive(List.of(image), false);
    // Rebuild the bundle with a link entry before its content to ensure links never reach import.
    Path linked = temporaryDirectory.resolve("linked.tar");
    try (var output = Files.newOutputStream(linked); var tar = new TarArchiveOutputStream(output)) {
      TarArchiveEntry link = new TarArchiveEntry("escape", TarArchiveEntry.LF_SYMLINK);
      link.setLinkName("../../outside");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
      try (var input = new org.apache.commons.compress.archivers.tar.TarFile(path)) {
        for (TarArchiveEntry entry : input.getEntries()) {
          TarArchiveEntry copy = new TarArchiveEntry(
              entry.getName(), entry.isDirectory() ? TarArchiveEntry.LF_DIR : TarArchiveEntry.LF_NORMAL);
          if (entry.isFile()) copy.setSize(entry.getSize());
          tar.putArchiveEntry(copy);
          if (entry.isFile()) input.getInputStream(entry).transferTo(tar);
          tar.closeArchiveEntry();
        }
      }
      tar.finish();
    }
    return linked;
  }

  private static byte[] tar(Map<String, byte[]> files) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(output)) {
      for (var file : files.entrySet()) add(tar, file.getKey(), file.getValue());
      tar.finish();
    }
    return output.toByteArray();
  }

  private static byte[] gzip(byte[] input) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(input);
    }
    return output.toByteArray();
  }

  private static byte[] tarWithLink() throws IOException {
    var output = new ByteArrayOutputStream();
    try (var tar = new TarArchiveOutputStream(output)) {
      TarArchiveEntry link = new TarArchiveEntry("app-link", TarArchiveEntry.LF_SYMLINK);
      link.setLinkName("../../outside");
      tar.putArchiveEntry(link);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return output.toByteArray();
  }

  private static String digest(byte[] content) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static void add(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }

  private static void addDirectory(TarArchiveOutputStream tar, String name) throws IOException {
    tar.putArchiveEntry(new TarArchiveEntry(name));
    tar.closeArchiveEntry();
  }

  private record Image(byte[] manifest, String manifestDigest, byte[] config, String configDigest, byte[] layer, String layerDigest) {}
}
