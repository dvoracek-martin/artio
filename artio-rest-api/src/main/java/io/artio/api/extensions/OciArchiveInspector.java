package io.artio.api.extensions;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.PluginManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** Validates a digest-pinned plugin bundle in an OCI image-layout tar without extracting paths. */
public final class OciArchiveInspector {
  private static final ObjectMapper JSON =
      new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern SHA256 = Pattern.compile("sha256:[a-f0-9]{64}");
  private static final Set<String> INDEX_TYPES =
      Set.of("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json");
  private static final Set<String> MANIFEST_TYPES =
      Set.of("application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json");
  private static final Set<String> CONFIG_TYPES =
      Set.of("application/vnd.oci.image.config.v1+json", "application/vnd.docker.container.image.v1+json");
  private static final Set<String> GZIP_LAYER_TYPES =
      Set.of(
          "application/vnd.oci.image.layer.v1.tar+gzip",
          "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
          "application/vnd.docker.image.rootfs.diff.tar.gzip");
  private static final Set<String> TAR_LAYER_TYPES =
      Set.of(
          "application/vnd.oci.image.layer.v1.tar",
          "application/vnd.oci.image.layer.nondistributable.v1.tar",
          "application/vnd.docker.image.rootfs.diff.tar");
  private static final int BUFFER_SIZE = 64 * 1024;

  private final Limits limits;

  public OciArchiveInspector(Limits limits) {
    this.limits = limits;
  }

  public Inspection inspect(Path archive, PluginManifest manifest) throws IOException {
    long archiveBytes = Files.size(archive);
    if (archiveBytes <= 0 || archiveBytes > limits.maximumArchiveBytes()) {
      throw invalid("OCI archive exceeds the configured upload limit");
    }

    var entries = new LinkedHashMap<String, TarArchiveEntry>();
    long declaredExpanded = 0;
    try (TarFile tar = new TarFile(archive, false)) {
      List<TarArchiveEntry> tarEntries = tar.getEntries();
      if (tarEntries.isEmpty() || tarEntries.size() > limits.maximumEntries()) {
        throw invalid("OCI archive has an invalid entry count");
      }
      for (TarArchiveEntry entry : tarEntries) {
        String path = safeArchivePath(entry.getName());
        if (entries.putIfAbsent(path, entry) != null) {
          throw invalid("OCI archive contains duplicate paths");
        }
        if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
            || entry.isBlockDevice() || entry.isFIFO() || entry.isSparse()) {
          throw invalid("OCI archive contains a link or special file");
        }
        if (entry.isDirectory()) {
          if (entry.getSize() != 0) throw invalid("OCI archive directory has a body");
        } else if (!entry.isFile() || entry.getSize() < 0) {
          throw invalid("OCI archive contains an unsupported tar entry");
        } else {
          declaredExpanded = addBounded(declaredExpanded, entry.getSize(), limits.maximumArchiveBytes());
        }
      }

      TarArchiveEntry layoutEntry = entries.get("oci-layout");
      TarArchiveEntry indexEntry = entries.get("index.json");
      if (entries.containsKey("manifest.json")) {
        return inspectDockerSave(tar, entries, manifest, archiveBytes, declaredExpanded);
      }
      if (entries.keySet().stream().anyMatch(path -> !isExpectedLayoutPath(path))) {
        throw invalid("OCI archive contains an unexpected path");
      }
      if (layoutEntry == null || layoutEntry.isDirectory() || indexEntry == null || indexEntry.isDirectory()) {
        throw invalid("OCI image layout is incomplete");
      }
      JsonNode layout = readJson(tar, layoutEntry, 16 * 1024);
      if (!"1.0.0".equals(layout.path("imageLayoutVersion").asText())) {
        throw invalid("OCI image layout version is unsupported");
      }
      JsonNode index = readJson(tar, indexEntry, limits.maximumJsonBytes());
      if (index.path("schemaVersion").asInt(-1) != 2 || !index.path("manifests").isArray()) {
        throw invalid("OCI image index is malformed");
      }

      Map<String, List<Descriptor>> blobs = indexBlobs(entries);
      Set<String> expectedRootDigests = expectedImageDigests(manifest);
      Map<String, Descriptor> rootDescriptors = new LinkedHashMap<>();
      for (JsonNode node : index.path("manifests")) {
        Descriptor descriptor = descriptor(node, "OCI index descriptor");
        if (rootDescriptors.putIfAbsent(descriptor.digest(), descriptor) != null) {
          throw invalid("OCI image index contains duplicate root descriptors");
        }
      }
      if (!rootDescriptors.keySet().equals(expectedRootDigests)) {
        throw invalid("OCI archive images do not exactly match the plugin manifest digests");
      }

      var referenced = new LinkedHashSet<String>();
      var verifiedRoots = new LinkedHashMap<String, ImageInventoryBuilder>();
      long[] expandedBytes = {0};
      int[] layerEntries = {0};
      for (Descriptor root : rootDescriptors.values()) {
        var queue = new ArrayDeque<Descriptor>();
        var visited = new HashSet<String>();
        queue.add(root);
        while (!queue.isEmpty()) {
          Descriptor descriptor = queue.removeFirst();
          if (!visited.add(descriptor.digest())) continue;
          referenced.add(descriptor.digest());
          TarArchiveEntry blob = requireBlob(tar, entries, blobs, descriptor);
          if (INDEX_TYPES.contains(descriptor.mediaType())) {
            JsonNode childIndex = readJson(tar, blob, limits.maximumJsonBytes());
            requireSchemaVersion(childIndex);
            JsonNode children = childIndex.path("manifests");
            if (!children.isArray() || children.isEmpty()) throw invalid("Nested OCI index is empty");
            for (JsonNode child : children) queue.add(descriptor(child, "nested OCI descriptor"));
          } else if (MANIFEST_TYPES.contains(descriptor.mediaType())) {
            JsonNode imageManifest = readJson(tar, blob, limits.maximumJsonBytes());
            requireSchemaVersion(imageManifest);
            Descriptor config = descriptor(imageManifest.path("config"), "OCI config descriptor");
            if (!CONFIG_TYPES.contains(config.mediaType())) throw invalid("OCI config media type is unsupported");
            referenced.add(config.digest());
            TarArchiveEntry configBlob = requireBlob(tar, entries, blobs, config);
            JsonNode configJson = readJson(tar, configBlob, limits.maximumJsonBytes());
            JsonNode layers = imageManifest.path("layers");
            JsonNode diffIds = configJson.path("rootfs").path("diff_ids");
            if (!layers.isArray() || !diffIds.isArray() || diffIds.size() != layers.size()
                || !"layers".equals(configJson.path("rootfs").path("type").asText())) {
              throw invalid("OCI config and layer inventory do not agree");
            }
            ImageInventoryBuilder rootInventory =
                verifiedRoots.computeIfAbsent(root.digest(), ignored -> new ImageInventoryBuilder());
            rootInventory.configDigests.add(config.digest());
            int layerIndex = 0;
            for (JsonNode layerNode : layers) {
              Descriptor layer = descriptor(layerNode, "OCI layer descriptor");
              if (!GZIP_LAYER_TYPES.contains(layer.mediaType()) && !TAR_LAYER_TYPES.contains(layer.mediaType())) {
                throw invalid("OCI layer media type is unsupported");
              }
              String diffId = diffIds.get(layerIndex++).asText();
              if (!SHA256.matcher(diffId).matches()) throw invalid("OCI layer diff ID is invalid");
              referenced.add(layer.digest());
              TarArchiveEntry layerBlob = requireBlobPathOnly(entries, blobs, layer);
              inspectLayer(tar, layerBlob, layer, diffId, expandedBytes, layerEntries);
              rootInventory.layerDigests.add(layer.digest());
            }
          } else {
            throw invalid("OCI image descriptor media type is unsupported");
          }
        }
        if (!verifiedRoots.containsKey(root.digest()) || verifiedRoots.get(root.digest()).configDigests.isEmpty()) {
          throw invalid("OCI image descriptor does not contain an image manifest");
        }
      }

      if (!blobs.keySet().equals(referenced)) {
        throw invalid("OCI archive contains unreferenced image data");
      }
      var inventories = new LinkedHashMap<String, ImageInventory>();
      verifiedRoots.forEach((digest, builder) -> inventories.put(
          digest, new ImageInventory(digest, List.copyOf(builder.configDigests), List.copyOf(builder.layerDigests))));
      return new Inspection(archiveBytes, declaredExpanded, Map.copyOf(inventories), blobs.size(), layerEntries[0], "OCI_LAYOUT");
    } catch (IllegalArgumentException malformed) {
      if (malformed.getMessage() != null && malformed.getMessage().startsWith("Invalid OCI archive:")) throw malformed;
      throw invalid("OCI archive is malformed", malformed);
    }
  }

  private Inspection inspectDockerSave(
      TarFile tar,
      Map<String, TarArchiveEntry> entries,
      PluginManifest manifest,
      long archiveBytes,
      long declaredExpanded)
      throws IOException {
    TarArchiveEntry manifestEntry = entries.get("manifest.json");
    if (manifestEntry == null || manifestEntry.isDirectory()) throw invalid("Docker-save archive manifest is missing");
    JsonNode dockerManifest = readJson(tar, manifestEntry, limits.maximumJsonBytes());
    Map<String, String> expectedRepositories = expectedImageRepositories(manifest);
    if (!dockerManifest.isArray() || dockerManifest.isEmpty() || dockerManifest.size() != expectedRepositories.size()
        || dockerManifest.size() > limits.maximumImages()) {
      throw invalid("Docker-save archive image inventory does not match the plugin manifest");
    }

    Set<String> referencedFiles = new HashSet<>();
    referencedFiles.add("manifest.json");
    if (entries.containsKey("repositories")) referencedFiles.add("repositories");
    Set<String> dockerConfigDigests = new HashSet<>();
    var images = new LinkedHashMap<String, ImageInventory>();
    long[] expandedBytes = {0};
    int[] layerEntries = {0};
    for (JsonNode savedImage : dockerManifest) {
      if (!savedImage.isObject() || !savedImage.path("Config").isTextual() || !savedImage.path("Layers").isArray()
          || !savedImage.path("RepoTags").isArray() || savedImage.path("RepoTags").isEmpty()) {
        throw invalid("Docker-save archive image descriptor is malformed");
      }
      String configPath = safeArchivePath(savedImage.path("Config").asText());
      String matchedReferenceDigest = null;
      for (JsonNode tag : savedImage.path("RepoTags")) {
        if (!tag.isTextual() || tag.asText().isBlank()) throw invalid("Docker-save repository tag is invalid");
        String repository = repositoryName(tag.asText());
        for (var expected : expectedRepositories.entrySet()) {
          if (expected.getValue().equals(repository)) {
            if (matchedReferenceDigest != null && !matchedReferenceDigest.equals(expected.getKey())) {
              throw invalid("Docker-save repository tag ambiguously matches multiple plugin images");
            }
            matchedReferenceDigest = expected.getKey();
          }
        }
      }
      if (matchedReferenceDigest == null || images.containsKey(matchedReferenceDigest)) {
        throw invalid("Docker-save archive repository tags do not match the plugin images");
      }

      TarArchiveEntry configEntry = requireRegularEntry(entries, configPath);
      JsonNode config = readJson(tar, configEntry, limits.maximumJsonBytes());
      MessageDigest configDigest = sha256();
      long configSize;
      try (InputStream input = tar.getInputStream(configEntry)) {
        configSize = digest(input, configDigest, limits.maximumJsonBytes());
      }
      String configSha = "sha256:" + HexFormat.of().formatHex(configDigest.digest());
      dockerConfigDigests.add(configSha);
      String expectedConfigName = configSha.substring("sha256:".length()) + ".json";
      String configFileName = configPath.substring(configPath.lastIndexOf('/') + 1);
      if (!(configFileName.equals(expectedConfigName)
              || configFileName.equals(expectedConfigName.substring(0, expectedConfigName.length() - ".json".length())))
          || configSize != configEntry.getSize()) {
        throw invalid("Docker-save config digest does not match its image ID");
      }
      JsonNode diffIds = config.path("rootfs").path("diff_ids");
      JsonNode layers = savedImage.path("Layers");
      if (!"layers".equals(config.path("rootfs").path("type").asText())
          || diffIds.size() != layers.size()) {
        throw invalid("Docker-save config and layer inventory do not agree");
      }
      referencedFiles.add(configPath);
      var layerDigests = new LinkedHashSet<String>();
      for (int index = 0; index < layers.size(); index++) {
        JsonNode layerNode = layers.get(index);
        if (!layerNode.isTextual()) throw invalid("Docker-save layer path is invalid");
        String layerPath = safeArchivePath(layerNode.asText());
        TarArchiveEntry layerEntry = requireRegularEntry(entries, layerPath);
        String diffId = diffIds.get(index).asText();
        if (!SHA256.matcher(diffId).matches()) throw invalid("Docker-save layer diff ID is invalid");
        inspectDockerLayer(tar, layerEntry, diffId, expandedBytes, layerEntries);
        referencedFiles.add(layerPath);
        layerDigests.add(diffId);
      }
      images.put(matchedReferenceDigest,
          new ImageInventory(null, List.of(configSha), List.copyOf(layerDigests)));
    }

    if (entries.containsKey("oci-layout") || entries.containsKey("index.json")) {
      referencedFiles.addAll(inspectDockerSaveOciMetadata(tar, entries, dockerConfigDigests));
    }

    Set<String> actualFiles = new HashSet<>();
    for (var entry : entries.entrySet()) if (!entry.getValue().isDirectory()) actualFiles.add(entry.getKey());
    if (!actualFiles.equals(referencedFiles) || !images.keySet().equals(expectedRepositories.keySet())) {
      throw invalid("Docker-save archive contains unreferenced image data or omits an image ("
          + actualFiles + " vs " + referencedFiles + ")");
    }
    return new Inspection(archiveBytes, declaredExpanded, Map.copyOf(images), referencedFiles.size() - 1,
        layerEntries[0], "DOCKER_SAVE");
  }

  private Set<String> inspectDockerSaveOciMetadata(
      TarFile tar, Map<String, TarArchiveEntry> entries, Set<String> dockerConfigDigests) throws IOException {
    TarArchiveEntry layoutEntry = entries.get("oci-layout");
    TarArchiveEntry indexEntry = entries.get("index.json");
    if (layoutEntry == null || indexEntry == null || !layoutEntry.isFile() || !indexEntry.isFile()) {
      throw invalid("Docker-save OCI metadata is incomplete");
    }
    JsonNode layout = readJson(tar, layoutEntry, 16 * 1024);
    if (!"1.0.0".equals(layout.path("imageLayoutVersion").asText())) {
      throw invalid("Docker-save OCI layout version is unsupported");
    }
    JsonNode index = readJson(tar, indexEntry, limits.maximumJsonBytes());
    if (index.path("schemaVersion").asInt(-1) != 2 || !index.path("manifests").isArray()
        || index.path("manifests").isEmpty() || index.path("manifests").size() > limits.maximumImages()) {
      throw invalid("Docker-save OCI index is malformed");
    }
    Map<String, List<Descriptor>> blobs = indexBlobs(entries);
    Set<String> referenced = new HashSet<>(Set.of("oci-layout", "index.json"));
    Set<String> ociConfigDigests = new HashSet<>();
    for (JsonNode node : index.path("manifests")) {
      Descriptor root = descriptor(node, "Docker-save OCI index descriptor");
      if (!MANIFEST_TYPES.contains(root.mediaType())) throw invalid("Docker-save OCI image manifest is unsupported");
      TarArchiveEntry manifestEntry = requireBlob(tar, entries, blobs, root);
      referenced.add("blobs/sha256/" + root.digest().substring(7));
      JsonNode imageManifest = readJson(tar, manifestEntry, limits.maximumJsonBytes());
      requireSchemaVersion(imageManifest);
      Descriptor config = descriptor(imageManifest.path("config"), "Docker-save OCI config descriptor");
      if (!CONFIG_TYPES.contains(config.mediaType())) throw invalid("Docker-save OCI config media type is unsupported");
      requireBlob(tar, entries, blobs, config);
      ociConfigDigests.add(config.digest());
      referenced.add("blobs/sha256/" + config.digest().substring(7));
      JsonNode layers = imageManifest.path("layers");
      if (!layers.isArray() || layers.isEmpty()) throw invalid("Docker-save OCI layer inventory is empty");
      for (JsonNode layerNode : layers) {
        Descriptor layer = descriptor(layerNode, "Docker-save OCI layer descriptor");
        if (!GZIP_LAYER_TYPES.contains(layer.mediaType()) && !TAR_LAYER_TYPES.contains(layer.mediaType())) {
          throw invalid("Docker-save OCI layer media type is unsupported");
        }
        requireBlob(tar, entries, blobs, layer);
        referenced.add("blobs/sha256/" + layer.digest().substring(7));
      }
    }
    if (!ociConfigDigests.equals(dockerConfigDigests)) {
      throw invalid("Docker-save OCI and legacy manifests refer to different images");
    }
    for (var blob : blobs.entrySet()) {
      TarArchiveEntry entry = entries.get("blobs/sha256/" + blob.getKey().substring(7));
      if (entry == null || !entry.isFile()) throw invalid("Docker-save content-addressed blob is invalid");
      MessageDigest digest = sha256();
      try (InputStream input = tar.getInputStream(entry)) {
        digest(input, digest, limits.maximumArchiveBytes());
      }
      if (!("sha256:" + HexFormat.of().formatHex(digest.digest())).equals(blob.getKey())) {
        throw invalid("Docker-save content-addressed blob digest is invalid");
      }
      referenced.add("blobs/sha256/" + blob.getKey().substring(7));
    }
    return referenced;
  }

  private void inspectDockerLayer(TarFile tar, TarArchiveEntry layer, String expectedDiffId,
      long[] expandedBytes, int[] entryCount) throws IOException {
    MessageDigest digest = sha256();
    Set<String> paths = new HashSet<>();
    try (InputStream source = tar.getInputStream(layer);
        InputStream checked = new java.security.DigestInputStream(source, digest);
        TarArchiveInputStream layerTar = new TarArchiveInputStream(checked)) {
      TarArchiveEntry entry;
      while ((entry = layerTar.getNextEntry()) != null) {
        entryCount[0]++;
        if (entryCount[0] > limits.maximumEntries()) throw invalid("Docker-save layers exceed the entry limit");
        String path = safeLayerPath(entry.getName());
        if (!paths.add(path)) throw invalid("Docker-save layer contains duplicate paths");
        if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
            || entry.isBlockDevice() || entry.isFIFO() || entry.isSparse()) {
          throw invalid("Docker-save layer contains a link or unsupported special file");
        }
        if ((!entry.isDirectory() && !entry.isFile()) || entry.getSize() < 0
            || (entry.isDirectory() && entry.getSize() != 0)) {
          throw invalid("Docker-save layer entry has an invalid type or size");
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        long readEntry = 0;
        int count;
        while ((count = layerTar.read(buffer)) != -1) {
          if (count == 0) continue;
          readEntry = addBounded(readEntry, count, limits.maximumExpandedBytes());
          expandedBytes[0] = addBounded(expandedBytes[0], count, limits.maximumExpandedBytes());
        }
        if (readEntry != entry.getSize()) throw invalid("Docker-save layer entry is truncated");
      }
      byte[] buffer = new byte[BUFFER_SIZE];
      int trailing;
      while ((trailing = checked.read(buffer)) != -1) {
        if (trailing > 0) expandedBytes[0] = addBounded(expandedBytes[0], trailing, limits.maximumExpandedBytes());
      }
    }
    if (!("sha256:" + HexFormat.of().formatHex(digest.digest())).equals(expectedDiffId)) {
      throw invalid("Docker-save uncompressed layer digest does not match its config diff ID");
    }
  }

  private static TarArchiveEntry requireRegularEntry(Map<String, TarArchiveEntry> entries, String path) {
    TarArchiveEntry entry = entries.get(path);
    if (entry == null || !entry.isFile()) throw invalid("Docker-save image data is missing or not a regular file");
    return entry;
  }

  private Map<String, String> expectedImageRepositories(PluginManifest manifest) {
    var result = new LinkedHashMap<String, String>();
    addExpectedRepository(result, manifest.image());
    manifest.services().forEach(service -> addExpectedRepository(result, service.image()));
    if (result.isEmpty() || result.size() > limits.maximumImages()
        || new HashSet<>(result.values()).size() != result.size()) {
      throw invalid("Plugin image inventory exceeds limits or has ambiguous repositories");
    }
    return result;
  }

  private static void addExpectedRepository(Map<String, String> expected, String image) {
    int marker = image.lastIndexOf("@sha256:");
    if (marker < 1 || !SHA256.matcher("sha256:" + image.substring(marker + 8)).matches()) {
      throw invalid("Plugin manifest image is not digest-pinned");
    }
    expected.put("sha256:" + image.substring(marker + 8), image.substring(0, marker));
  }

  private static String repositoryName(String taggedImage) {
    int slash = taggedImage.lastIndexOf('/');
    int colon = taggedImage.lastIndexOf(':');
    return colon > slash ? taggedImage.substring(0, colon) : taggedImage;
  }

  private void inspectLayer(
      TarFile tar,
      TarArchiveEntry layerBlob,
      Descriptor layer,
      String expectedDiffId,
      long[] expandedBytes,
      int[] entryCount)
      throws IOException {
    MessageDigest digest = sha256();
    MessageDigest diffDigest = sha256();
    long[] compressedBytes = {0};
    long layerExpandedBytes = 0;
    Set<String> paths = new HashSet<>();
    try (InputStream raw = tar.getInputStream(layerBlob);
        InputStream checked = new CountingDigestInputStream(raw, digest, compressedBytes);
        InputStream decompressed =
            GZIP_LAYER_TYPES.contains(layer.mediaType())
                ? new GzipCompressorInputStream(checked, false)
                : checked;
        InputStream expanded = new java.security.DigestInputStream(decompressed, diffDigest);
        TarArchiveInputStream layerTar = new TarArchiveInputStream(expanded)) {
      TarArchiveEntry entry;
      while ((entry = layerTar.getNextEntry()) != null) {
        entryCount[0]++;
        if (entryCount[0] > limits.maximumEntries()) throw invalid("OCI image layers exceed the entry limit");
        String path = safeLayerPath(entry.getName());
        if (!paths.add(path)) throw invalid("OCI image layer contains duplicate paths");
        if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
            || entry.isBlockDevice() || entry.isFIFO() || entry.isSparse()) {
          throw invalid("OCI image layer contains a link or unsupported special file");
        }
        if ((!entry.isDirectory() && !entry.isFile()) || entry.getSize() < 0
            || (entry.isDirectory() && entry.getSize() != 0)) {
          throw invalid("OCI image layer has an invalid entry type or size");
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        long entryRead = 0;
        int count;
        while ((count = layerTar.read(buffer)) != -1) {
          if (count == 0) continue;
          entryRead = addBounded(entryRead, count, limits.maximumExpandedBytes());
          layerExpandedBytes = addBounded(layerExpandedBytes, count, limits.maximumExpandedBytes());
          expandedBytes[0] = addBounded(expandedBytes[0], count, limits.maximumExpandedBytes());
        }
        if (entryRead != entry.getSize()) throw invalid("OCI image layer entry is truncated");
      }
      byte[] buffer = new byte[BUFFER_SIZE];
      int trailing;
      while ((trailing = expanded.read(buffer)) != -1) {
        if (trailing > 0) {
          layerExpandedBytes = addBounded(layerExpandedBytes, trailing, limits.maximumExpandedBytes());
          expandedBytes[0] = addBounded(expandedBytes[0], trailing, limits.maximumExpandedBytes());
        }
      }
    } catch (org.apache.commons.compress.compressors.CompressorException malformed) {
      throw invalid("OCI layer compression is malformed", malformed);
    }
    if (compressedBytes[0] != layerBlob.getSize()
        || !("sha256:" + HexFormat.of().formatHex(digest.digest())).equals(layer.digest())) {
      throw invalid("OCI layer digest or size does not match its descriptor");
    }
    if (!("sha256:" + HexFormat.of().formatHex(diffDigest.digest())).equals(expectedDiffId)) {
      throw invalid("OCI uncompressed layer digest does not match its config diff ID");
    }
    long maximumLayerExpansion = layerBlob.getSize() > Long.MAX_VALUE / limits.maximumExpansionRatio()
        ? Long.MAX_VALUE
        : layerBlob.getSize() * limits.maximumExpansionRatio();
    if (layerExpandedBytes > maximumLayerExpansion) {
      throw invalid("OCI layer exceeds the configured decompression ratio");
    }
  }

  private Map<String, List<Descriptor>> indexBlobs(Map<String, TarArchiveEntry> entries) {
    var blobs = new HashMap<String, List<Descriptor>>();
    for (var item : entries.entrySet()) {
      if (!item.getKey().startsWith("blobs/sha256/")) continue;
      String digest = "sha256:" + item.getKey().substring("blobs/sha256/".length());
      if (!SHA256.matcher(digest).matches() || item.getValue().isDirectory()) {
        throw invalid("OCI archive blob path is invalid");
      }
      blobs.put(digest, List.of(new Descriptor(digest, item.getValue().getSize(), "")));
    }
    return blobs;
  }

  private TarArchiveEntry requireBlob(
      TarFile tar,
      Map<String, TarArchiveEntry> entries,
      Map<String, List<Descriptor>> blobs,
      Descriptor descriptor)
      throws IOException {
    TarArchiveEntry entry = requireBlobPathOnly(entries, blobs, descriptor);
    MessageDigest digest = sha256();
    long size;
    try (InputStream input = tar.getInputStream(entry)) {
      size = digest(input, digest, limits.maximumArchiveBytes());
    }
    if (size != descriptor.size() || !("sha256:" + HexFormat.of().formatHex(digest.digest())).equals(descriptor.digest())) {
      throw invalid("OCI blob digest does not match its descriptor");
    }
    return entry;
  }

  private TarArchiveEntry requireBlobPathOnly(
      Map<String, TarArchiveEntry> entries, Map<String, List<Descriptor>> blobs, Descriptor descriptor) {
    if (!blobs.containsKey(descriptor.digest())) throw invalid("OCI descriptor references a missing blob");
    TarArchiveEntry entry = entries.get("blobs/sha256/" + descriptor.digest().substring(7));
    if (entry == null || entry.getSize() != descriptor.size()) throw invalid("OCI descriptor size is incorrect");
    return entry;
  }

  private JsonNode readJson(TarFile tar, TarArchiveEntry entry, int maximumBytes) throws IOException {
    if (entry.getSize() < 0 || entry.getSize() > maximumBytes) throw invalid("OCI metadata exceeds its size limit");
    byte[] bytes;
    try (InputStream input = tar.getInputStream(entry); var output = new ByteArrayOutputStream((int) entry.getSize())) {
      input.transferTo(output);
      bytes = output.toByteArray();
    }
    if (bytes.length != entry.getSize()) throw invalid("OCI metadata entry is truncated");
    try {
      return JSON.readTree(bytes);
    } catch (IOException malformed) {
      throw invalid("OCI metadata JSON is malformed", malformed);
    }
  }

  private Descriptor descriptor(JsonNode node, String field) {
    if (node == null || !node.isObject() || node.has("urls")) throw invalid(field + " is invalid");
    if (!node.path("digest").isTextual() || !node.path("mediaType").isTextual()
        || !node.path("size").isIntegralNumber()) throw invalid(field + " fields have invalid types");
    String digest = node.path("digest").asText();
    String mediaType = node.path("mediaType").asText();
    long size = node.path("size").asLong(-1);
    if (!SHA256.matcher(digest).matches() || mediaType.isBlank() || size <= 0) {
      throw invalid(field + " has invalid digest, media type, or size");
    }
    return new Descriptor(digest, size, mediaType);
  }

  private Set<String> expectedImageDigests(PluginManifest manifest) {
    var expected = new LinkedHashSet<String>();
    addExpected(expected, manifest.image());
    manifest.services().forEach(service -> addExpected(expected, service.image()));
    if (expected.isEmpty() || expected.size() > limits.maximumImages()) {
      throw invalid("Plugin image inventory exceeds the configured limit");
    }
    return expected;
  }

  private void addExpected(Set<String> digests, String image) {
    int marker = image.lastIndexOf("@sha256:");
    if (marker < 1) throw invalid("Plugin image is not pinned to a SHA-256 digest");
    String digest = image.substring(marker + 1);
    if (!SHA256.matcher(digest).matches()) throw invalid("Plugin image digest is invalid");
    digests.add(digest);
  }

  private static void requireSchemaVersion(JsonNode node) {
    if (node.path("schemaVersion").asInt(-1) != 2) throw invalid("OCI manifest schema version is unsupported");
  }

  private static String safeArchivePath(String value) {
    if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")
        || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.matches("^[A-Za-z]:.*")) {
      throw invalid("OCI archive contains an unsafe path");
    }
    String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    if (normalized.isBlank()) throw invalid("OCI archive contains an unsafe path");
    for (String component : normalized.split("/")) {
      if (component.isBlank() || component.equals(".") || component.equals("..")) {
        throw invalid("OCI archive contains an unsafe path");
      }
    }
    return normalized;
  }

  private static String safeLayerPath(String value) {
    if (value == null || value.isBlank() || value.startsWith("/") || value.startsWith("\\")
        || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.matches("^[A-Za-z]:.*")) {
      throw invalid("OCI image layer contains an unsafe path");
    }
    for (String component : value.split("/")) {
      if (component.isBlank() || component.equals(".") || component.equals("..")) {
        throw invalid("OCI image layer contains path traversal or an ambiguous path");
      }
    }
    return value;
  }

  private static boolean isExpectedLayoutPath(String path) {
    return path.equals("oci-layout")
        || path.equals("index.json")
        || path.equals("blobs")
        || path.equals("blobs/sha256")
        || path.matches("blobs/sha256/[a-f0-9]{64}");
  }

  private static long digest(InputStream input, MessageDigest digest, long maximum) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      total = addBounded(total, count, maximum);
      digest.update(buffer, 0, count);
    }
    return total;
  }

  private static long addBounded(long current, long increment, long maximum) {
    if (increment < 0 || current > maximum - increment) throw invalid("OCI archive exceeds an expanded-size limit");
    return current + increment;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid(String reason) {
    return new IllegalArgumentException("Invalid OCI archive: " + reason);
  }

  private static IllegalArgumentException invalid(String reason, Throwable cause) {
    return new IllegalArgumentException("Invalid OCI archive: " + reason, cause);
  }

  private record Descriptor(String digest, long size, String mediaType) {}

  public record Limits(
      long maximumArchiveBytes,
      long maximumExpandedBytes,
      long maximumExpansionRatio,
      int maximumEntries,
      int maximumImages,
      int maximumJsonBytes) {
    public Limits {
      if (maximumArchiveBytes <= 0
          || maximumExpandedBytes <= 0
          || maximumExpansionRatio <= 0
          || maximumEntries <= 0
          || maximumImages <= 0
          || maximumJsonBytes <= 0) throw new IllegalArgumentException("OCI archive limits must be positive");
    }

    public static Limits developmentDefaults() {
      return new Limits(2L << 30, 8L << 30, 200, 100_000, 16, 4 << 20);
    }
  }

  /** manifestDigest is null for docker-save because that archive format carries no registry manifest digest. */
  public record ImageInventory(String manifestDigest, List<String> configDigests, List<String> layerDigests) {}

  public record Inspection(
      long archiveBytes,
      long declaredArchiveBytes,
      Map<String, ImageInventory> images,
      int blobCount,
      int expandedLayerEntries,
      String sourceFormat) {}

  private static final class ImageInventoryBuilder {
    private final List<String> configDigests = new ArrayList<>();
    private final List<String> layerDigests = new ArrayList<>();
  }

  private static final class CountingDigestInputStream extends java.security.DigestInputStream {
    private final long[] count;

    private CountingDigestInputStream(InputStream input, MessageDigest digest, long[] count) {
      super(input, digest);
      this.count = count;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) count[0]++;
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = super.read(buffer, offset, length);
      if (read > 0) count[0] += read;
      return read;
    }
  }
}
