package io.artio.api.extensions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.artio.api.security.ExtensionCredentialService;
import io.artio.extensions.PluginManifest;
import io.artio.extensions.ExtensionRuntimePort;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Restricted Docker runtime for digest-pinned extensions. The Docker daemon is an administrative trust boundary. */
public final class DockerExtensionRuntime implements ExtensionRuntimePort {
    private static final String EGRESS_PROXY = "http://extension-egress:3128";
    private static final String SECRET_FILE_HELPER_IMAGE = "python:3.12-alpine@sha256:1b668429b3511ab407d8e00648891631b0b1a4d7e15e3ca70f38ab5b91ad4ab4";
    private final int port;
    private static final long MAX_REQUEST_BYTES = 1_048_576;
    private static final long MAX_RESPONSE_BYTES = 1_048_576;
    private final DockerClient docker;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String network;
    private final ExtensionCredentialService credentials;
    private final String egressProxy;
    private final boolean hostLoopbackTestAccess;
    private final String testRunId;
    private final java.util.concurrent.ConcurrentMap<String, ExtensionCircuitBreaker> circuits = new java.util.concurrent.ConcurrentHashMap<>();

    public DockerExtensionRuntime(DockerClient docker,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.network:artio-extensions}") String network,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.port:8080}") int port,
            ExtensionCredentialService credentials,
            @org.springframework.beans.factory.annotation.Value("${artio.extensions.docker.egress-proxy:http://extension-egress:3128}") String egressProxy) {
        this(docker, network, port, credentials, false, egressProxy);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials) {
        this(docker, network, port, credentials, false, EGRESS_PROXY);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials,
                           boolean hostLoopbackTestAccess) {
        this(docker, network, port, credentials, hostLoopbackTestAccess, EGRESS_PROXY);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials,
                           boolean hostLoopbackTestAccess, String egressProxy) {
        this(docker, network, port, credentials, hostLoopbackTestAccess, egressProxy, null);
    }

    DockerExtensionRuntime(DockerClient docker, String network, int port, ExtensionCredentialService credentials,
                           boolean hostLoopbackTestAccess, String egressProxy, String testRunId) {
        this.docker = docker;
        this.network = network;
        this.port = port;
        this.credentials = credentials;
        this.egressProxy = egressProxy;
        this.hostLoopbackTestAccess = hostLoopbackTestAccess;
        this.testRunId = testRunId;
    }

    public void install(PluginManifest manifest) {
        install(manifest, Map.of());
    }

    @Override
    public void install(PluginManifest manifest, Map<String, ExtensionRuntimePort.RegistryAuth> registryCredentials) {
        for (RuntimeService service : services(manifest)) {
            pullImage(service.image(), registryCredentials);
        }
    }

    @Override
    public void importLocalArchive(Path archive, Map<String, List<String>> manifestConfigDigests) {
        ExtensionRuntimePort.super.importLocalArchive(archive, manifestConfigDigests);
    }

    @Override
    public void importLocalArchive(InputStream archive, Map<String, List<String>> manifestConfigDigests) {
        if (manifestConfigDigests == null || manifestConfigDigests.isEmpty()) {
            throw new IllegalArgumentException("Verified OCI image inventory is required");
        }
        for (var image : manifestConfigDigests.entrySet()) {
            if (image.getKey() == null || !image.getKey().matches("sha256:[a-f0-9]{64}")
                || image.getValue() == null || image.getValue().isEmpty() || image.getValue().size() > 16
                || image.getValue().stream().anyMatch(config -> config == null || !config.matches("sha256:[a-f0-9]{64}"))) {
                throw new IllegalArgumentException("Verified OCI image inventory is invalid");
            }
        }
        try {
            docker.loadImageCmd(archive).exec();
            for (var image : manifestConfigDigests.entrySet()) {
                String digest = image.getKey();
                boolean tagged = false;
                for (String configDigest : image.getValue()) {
                    try {
                        docker.inspectImageCmd(configDigest).exec();
                        docker.tagImageCmd(configDigest, "artio-local-cache", localTag(digest)).withForce(true).exec();
                        tagged = true;
                        break;
                    } catch (NotFoundException unavailablePlatform) {
                        // Docker only loads the image matching its own platform from a multi-platform archive.
                    }
                }
                if (!tagged) throw new IllegalStateException("Verified local OCI image was not loaded by Docker");
            }
        } catch (RuntimeException failure) {
            throw failure;
        }
    }

    private void pullImage(String image, Map<String, ExtensionRuntimePort.RegistryAuth> registryCredentials) {
        requirePinnedImage(image);
        if (hasLocalImage(image)) return;
        boolean completed;
        var pull = docker.pullImageCmd(image);
        int separator = image.indexOf('/');
        String firstComponent = separator < 0 ? "" : image.substring(0, separator);
        String registryHost = separator < 0 || !(firstComponent.contains(".") || firstComponent.contains(":")
                || "localhost".equals(firstComponent)) ? "docker.io" : firstComponent;
        ExtensionRuntimePort.RegistryAuth registryAuth = registryCredentials.get(registryHost);
        if (registryAuth != null) {
            pull.withAuthConfig(new AuthConfig()
                    .withUsername(registryAuth.username())
                    .withPassword(registryAuth.password())
                    .withRegistryAddress(registryHost));
        }
        try (var callback = pull.start()) {
            completed = callback.awaitCompletion(120, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Extension image pull was interrupted for " + image, interrupted);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Extension image pull failed for " + image, failure);
        }
        if (!completed) throw new IllegalStateException("Extension image pull timed out for " + image + " after 120 seconds");
    }

    @Override
    public void installInstance(String runtimeIdentity, PluginManifest manifest) {
        installInstance(runtimeIdentity, manifest, Map.of());
    }

    @Override
    public void installInstance(
            String runtimeIdentity,
            PluginManifest manifest,
            Map<String, ExtensionRuntimePort.RegistryAuth> registryCredentials) {
        validateRuntimeIdentity(runtimeIdentity);
        install(manifest, registryCredentials);
    }

    @Override
    public void update(PluginManifest manifest) {
        update(manifest, Map.of());
    }

    @Override
    public void update(PluginManifest manifest, Map<String, ExtensionRuntimePort.RegistryAuth> registryCredentials) {
        requirePinnedImage(manifest.image());
        install(manifest, registryCredentials); // Pull first: a failed download leaves the current stopped runtime intact.
        removeStoppedManagedContainer(manifest.id());
    }

    public void enable(PluginManifest manifest) {
        enable(manifest, 1);
    }

    @Override
    public void enable(PluginManifest manifest, int credentialGeneration) {
        enable(manifest, credentialGeneration, Map.of());
    }

    @Override
    public void enable(PluginManifest manifest, int credentialGeneration, Map<String, String> configuration) {
        enableInstance(manifest.id(), manifest, credentialGeneration, configuration);
    }

    @Override
    public void enableInstance(String runtimeIdentity, PluginManifest manifest, int credentialGeneration,
            Map<String, String> configuration) {
        validateRuntimeIdentity(runtimeIdentity);
        if (credentialGeneration < 1) throw new IllegalArgumentException("Credential generation must be positive");
        try {
            for (RuntimeService service : servicesInStartupOrder(manifest)) {
                startService(runtimeIdentity, manifest, service, credentialGeneration, configuration);
                awaitReady(runtimeIdentity, manifest.id(), service.id(), service.port(), service.healthPath());
            }
        } catch (RuntimeException failure) {
            disableInstance(runtimeIdentity, manifest.id());
            throw failure;
        }
    }

    private void startService(String runtimeIdentity, PluginManifest manifest, RuntimeService service, int credentialGeneration,
            Map<String, String> configuration) {
        String name = serviceName(runtimeIdentity, service.id());
        var environment = environment(runtimeIdentity, manifest, service, credentialGeneration, configuration);
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(name).exec();
            requireManaged(current, manifest.id(), runtimeIdentity);
            if (Boolean.TRUE.equals(current.getState().getRunning())) return;
            var existingEnvironment = current.getConfig() == null ? null : current.getConfig().getEnv();
            if (service.secretFileBindings().isEmpty() && existingEnvironment != null
                    && java.util.Arrays.equals(existingEnvironment, environment.toArray(String[]::new))) {
                docker.startContainerCmd(name).exec();
                return;
            }
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
        } catch (NotFoundException ignored) {
            // Create only containers owned by this runtime; no mounts, published ports or caller-supplied options.
        }
        HostConfig host = restrictedHostConfig(network, service.memoryBytes());
        var serviceBinds = new java.util.ArrayList<Bind>();
        if (!service.persistentPaths().isEmpty()) serviceBinds.addAll(persistentBinds(manifest.id(), runtimeIdentity, service));
        SecretVolumeMount secretVolume = null;
        if (hostLoopbackTestAccess) {
            var bindings = new com.github.dockerjava.api.model.Ports();
            bindings.bind(ExposedPort.tcp(service.port()), com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"));
            host.withPortBindings(bindings);
        }
        var labels = new java.util.HashMap<String, String>();
        labels.put("io.artio.extension", manifest.id());
        labels.put("io.artio.extension.service", service.id());
        labels.put("io.artio.extension.runtime", runtimeIdentity);
        labels.put("io.artio.extension.startup-order", Integer.toString(service.startupOrder()));
        labels.put("io.artio.extension.ports", service.internalPorts().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        labels.put("io.artio.managed", "true");
        if (testRunId != null && !testRunId.isBlank()) labels.put("io.artio.test.run", testRunId);
        try {
            if (!service.secretFileBindings().isEmpty()) {
                secretVolume = populateSecretFileVolume(manifest, runtimeIdentity, service, configuration);
                serviceBinds.add(new Bind(secretVolume.volumeName(), new Volume("/run/secrets"), AccessMode.ro));
            }
            if (!serviceBinds.isEmpty()) host.withBinds(serviceBinds);
            var create = docker.createContainerCmd(resolveImage(service.image())).withName(name).withHostConfig(host)
                    .withUser("10001:10001")
                    .withLabels(labels);
            var exposedPorts = new java.util.ArrayList<ExposedPort>();
            exposedPorts.add(ExposedPort.tcp(service.port()));
            service.internalPorts().forEach(additional -> exposedPorts.add(ExposedPort.tcp(additional)));
            create.withExposedPorts(exposedPorts);
            create.withEnv(environment);
            create.exec();
            docker.startContainerCmd(name).exec();
        } finally {
            // Keep the tmpfs mounted by the helper until Docker has attached the plugin container.
            // Removing the helper earlier unmounts a local tmpfs volume and discards its contents.
            if (secretVolume != null) removeSecretVolumeHelper(secretVolume.helperContainerId());
        }
    }

    private SecretVolumeMount populateSecretFileVolume(PluginManifest manifest, String runtimeIdentity, RuntimeService service,
            Map<String, String> configuration) {
        String volumeName = volumeName(runtimeIdentity, service.id(), "/run/secrets");
        String helperName = "artio-secret-init-" + volumeName.substring("artio-vol-".length());
        var volumeLabels = managedResourceLabels(manifest.id(), runtimeIdentity, service.id());
        volumeLabels.put("io.artio.extension.secret-files", "true");
        docker.listVolumesCmd().exec().getVolumes().stream().filter(volume -> volumeName.equals(volume.getName())).findFirst()
                .ifPresent(volume -> {
                    if (!volumeLabels.equals(volume.getLabels())) {
                        throw new IllegalStateException("Refusing to replace an unmanaged extension secret volume");
                    }
                    docker.removeVolumeCmd(volumeName).exec();
                });
        var volume = docker.createVolumeCmd().withName(volumeName).withDriver("local")
                .withDriverOpts(Map.of("type", "tmpfs", "device", "tmpfs",
                        "o", "size=1m,uid=10001,gid=10001,mode=0700,noexec,nosuid,nodev"))
                .withLabels(volumeLabels).exec();
        if (!volumeLabels.equals(volume.getLabels())) {
            throw new IllegalStateException("Refusing to use an unmanaged extension secret volume");
        }
        String helperId = null;
        boolean populated = false;
        try {
            pullImage(SECRET_FILE_HELPER_IMAGE, Map.of());
            var helperLabels = managedResourceLabels(manifest.id(), runtimeIdentity, service.id());
            helperLabels.put("io.artio.extension.secret-writer", "true");
            var helperHost = restrictedHostConfig("none", 134_217_728L)
                    .withReadonlyRootfs(false)
                    .withBinds(List.of(new Bind(volumeName, new Volume("/run/secrets"), AccessMode.rw)));
            helperId = docker.createContainerCmd(SECRET_FILE_HELPER_IMAGE).withName(helperName)
                    .withUser("10001:10001").withCmd("python3", "-c", "import time; time.sleep(120)")
                    .withLabels(helperLabels).withHostConfig(helperHost).exec().getId();
            byte[] archive = secretFilesArchive(service.secretFileBindings(), manifest.configuration(), configuration);
            docker.startContainerCmd(helperId).exec();
            try (var input = new java.io.ByteArrayInputStream(archive)) {
                docker.copyArchiveToContainerCmd(helperId).withRemotePath("/run/secrets")
                        .withTarInputStream(input).withCopyUIDGID(true).exec();
            } finally {
                java.util.Arrays.fill(archive, (byte) 0);
            }
            populated = true;
            return new SecretVolumeMount(volumeName, helperId);
        } catch (IOException failure) {
            throw new IllegalStateException("Plugin secret files could not be delivered", failure);
        } finally {
            if (!populated) {
                removeSecretVolumeHelper(helperId);
                try { docker.removeVolumeCmd(volumeName).exec(); }
                catch (NotFoundException ignored) { }
            }
        }
    }

    private void removeSecretVolumeHelper(String helperContainerId) {
        if (helperContainerId == null) return;
        try { docker.removeContainerCmd(helperContainerId).withForce(true).exec(); }
        catch (NotFoundException ignored) { }
    }

    private record SecretVolumeMount(String volumeName, String helperContainerId) { }

    private java.util.HashMap<String, String> managedResourceLabels(String pluginId, String runtimeIdentity, String serviceId) {
        var labels = new java.util.HashMap<String, String>();
        labels.put("io.artio.extension", pluginId);
        labels.put("io.artio.extension.runtime", runtimeIdentity);
        labels.put("io.artio.extension.service", serviceId);
        labels.put("io.artio.managed", "true");
        if (testRunId != null && !testRunId.isBlank()) labels.put("io.artio.test.run", testRunId);
        return labels;
    }

    static byte[] secretFilesArchive(Map<String, String> bindings, Map<String, PluginManifest.ConfigField> schema,
            Map<String, String> configuration) throws IOException {
        if (bindings == null || bindings.isEmpty()) return new byte[0];
        if (bindings.size() > 32) throw new IllegalArgumentException("Plugin secret file count exceeds its limit");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bytes)) {
            tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_ERROR);
            long total = 0;
            for (var binding : bindings.entrySet()) {
                String path = binding.getKey();
                String key = binding.getValue();
                PluginManifest.ConfigField field = key == null ? null : schema.get(key);
                if (path == null || !path.matches("/run/secrets/[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                        || path.contains("..") || field == null || !"secret".equals(field.type()) || !field.secret()) {
                    throw new IllegalArgumentException("Plugin secret file binding is invalid");
                }
                String value = configuration.get(key);
                if (value == null) {
                    if (field.required()) throw new IllegalStateException("Required plugin secret is missing");
                    continue;
                }
                byte[] content = value.getBytes(StandardCharsets.UTF_8);
                if (content.length > 10_000 || (total += content.length) > 900_000) {
                    throw new IllegalArgumentException("Plugin secret files exceed their size limit");
                }
                String filename = path.substring("/run/secrets/".length());
                var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(filename);
                entry.setSize(content.length);
                entry.setMode(0600);
                entry.setUserId(10001);
                entry.setGroupId(10001);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return bytes.toByteArray();
    }

    private boolean hasLocalImage(String image) {
        String alias = localAlias(image);
        if (alias == null) return false;
        try {
            docker.inspectImageCmd(alias).exec();
            return true;
        } catch (NotFoundException notImported) {
            return false;
        }
    }

    private String resolveImage(String image) {
        String alias = localAlias(image);
        return alias != null && hasLocalImage(image) ? alias : image;
    }

    private static String localAlias(String image) {
        if (image == null) return null;
        int marker = image.lastIndexOf("@sha256:");
        if (marker < 1) return null;
        String digest = image.substring(marker + 1);
        if (!digest.matches("sha256:[a-f0-9]{64}")) return null;
        return "artio-local-cache:" + localTag(digest);
    }

    private static String localTag(String digest) {
        return "ref-sha256-" + digest.substring("sha256:".length());
    }

    private java.util.List<String> environment(String runtimeIdentity, PluginManifest manifest, RuntimeService service,
            int credentialGeneration, Map<String, String> configuration) {
        return resolvedEnvironment(runtimeIdentity, manifest, service.primary(), service.configurationBindings(),
                credentialGeneration, configuration, credentials, egressProxy);
    }

    static java.util.List<String> resolvedEnvironment(PluginManifest manifest, boolean primary,
            Map<String, String> bindings, int credentialGeneration, Map<String, String> configuration,
            io.artio.api.security.ExtensionCredentialService credentials) {
        return resolvedEnvironment(manifest, primary, bindings, credentialGeneration, configuration, credentials, EGRESS_PROXY);
    }

    static java.util.List<String> resolvedEnvironment(PluginManifest manifest, boolean primary,
            Map<String, String> bindings, int credentialGeneration, Map<String, String> configuration,
            io.artio.api.security.ExtensionCredentialService credentials, String egressProxy) {
        return resolvedEnvironment(manifest.id(), manifest, primary, bindings, credentialGeneration, configuration, credentials, egressProxy);
    }

    static java.util.List<String> resolvedEnvironment(String runtimeIdentity, PluginManifest manifest, boolean primary,
            Map<String, String> bindings, int credentialGeneration, Map<String, String> configuration,
            io.artio.api.security.ExtensionCredentialService credentials, String egressProxy) {
        var values = new java.util.TreeMap<String, String>();
        if (egressProxy != null && !egressProxy.isBlank()) {
            values.put("HTTP_PROXY", egressProxy);
            values.put("HTTPS_PROXY", egressProxy);
            values.put("http_proxy", egressProxy);
            values.put("https_proxy", egressProxy);
            values.put("NO_PROXY", "localhost,127.0.0.1,::1");
            values.put("no_proxy", "localhost,127.0.0.1,::1");
        }
        if (primary) {
            values.put("ARTIO_EXTENSION_TOKEN", credentials.derive(runtimeIdentity, credentialGeneration));
            values.put("ARTIO_EXTENSION_API_VERSION", "1");
        }
        for (var binding : bindings.entrySet()) {
            String value = configuration.get(binding.getValue());
            PluginManifest.ConfigField field = manifest.configuration().get(binding.getValue());
            if (value == null) {
                if (field != null && field.required()) throw new IllegalStateException("Required plugin configuration is missing");
                continue;
            }
            if (value.length() > 10_000) throw new IllegalArgumentException("Plugin configuration value is too large for a container environment");
            values.put(binding.getKey(), value);
        }
        if (values.size() > 100) throw new IllegalArgumentException("Plugin container environment exceeds its limit");
        long environmentBytes = values.entrySet().stream().mapToLong(entry -> entry.getKey().getBytes(StandardCharsets.UTF_8).length
                + entry.getValue().getBytes(StandardCharsets.UTF_8).length).sum();
        if (environmentBytes > 900_000) throw new IllegalArgumentException("Plugin container environment exceeds its size limit");
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    static HostConfig restrictedHostConfig(String network) {
        return restrictedHostConfig(network, 268_435_456L);
    }

    static HostConfig restrictedHostConfig(String network, long memoryBytes) {
        if (memoryBytes < 134_217_728L || memoryBytes > 2_147_483_648L) throw new IllegalArgumentException("Plugin memory limit is outside supported bounds");
        return HostConfig.newHostConfig().withNetworkMode(network).withMemory(memoryBytes)
                .withNanoCPUs(1_000_000_000L).withPidsLimit(128L).withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL).withSecurityOpts(java.util.List.of("no-new-privileges:true"))
                .withTmpFs(Map.of("/tmp", "rw,noexec,nosuid,size=16m"));
    }

    private java.util.List<Bind> persistentBinds(String pluginId, String runtimeIdentity, RuntimeService service) {
        return service.persistentPaths().stream().map(path -> {
            String name = volumeName(runtimeIdentity, service.id(), path);
            var labels = new java.util.HashMap<String, String>();
            labels.put("io.artio.extension", pluginId);
            labels.put("io.artio.extension.runtime", runtimeIdentity);
            labels.put("io.artio.extension.service", service.id());
            labels.put("io.artio.managed", "true");
            if (testRunId != null && !testRunId.isBlank()) labels.put("io.artio.test.run", testRunId);
            var created = docker.createVolumeCmd().withName(name).withLabels(labels).exec();
            if (!labels.equals(created.getLabels())) throw new IllegalStateException("Refusing to mount an unmanaged extension volume");
            return new Bind(name, new Volume(path), AccessMode.rw);
        }).toList();
    }

    static String volumeName(String pluginId, String serviceId, String path) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            String suffix = java.util.HexFormat.of().formatHex(digest).substring(0, 16);
            String identity = containerName(pluginId).substring("artio-ext-".length());
            if (serviceId == null || !serviceId.matches("[a-z][a-z0-9-]{0,30}")) throw new IllegalArgumentException("Invalid plugin service id");
            return "artio-vol-" + identity + "-" + serviceId + "-" + suffix;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public void disable(String pluginId) {
        var containers = new java.util.ArrayList<>(managedContainers(pluginId));
        containers.sort(java.util.Comparator.comparingInt(DockerExtensionRuntime::startupOrder).reversed());
        for (InspectContainerResponse current : containers) {
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(current.getId()).withTimeout(10).exec();
        }
    }

    @Override
    public void disableInstance(String runtimeIdentity, String pluginId) {
        var containers = new java.util.ArrayList<>(managedRuntimeContainers(runtimeIdentity));
        containers.sort(java.util.Comparator.comparingInt(DockerExtensionRuntime::startupOrder).reversed());
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId, runtimeIdentity);
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(current.getId()).withTimeout(10).exec();
        }
    }

    @Override
    public void uninstall(String pluginId) {
        var containers = managedContainers(pluginId);
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId);
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before uninstalling it");
            }
        }
        var volumeResponse = docker.listVolumesCmd().exec();
        var listedVolumes = volumeResponse == null || volumeResponse.getVolumes() == null ? List.<com.github.dockerjava.api.command.InspectVolumeResponse>of() : volumeResponse.getVolumes();
        var volumes = listedVolumes.stream()
                .filter(volume -> volume.getLabels() != null && pluginId.equals(volume.getLabels().get("io.artio.extension")))
                .toList();
        for (var volume : volumes) {
            if (!"true".equals(volume.getLabels().get("io.artio.managed"))) {
                throw new IllegalStateException("Refusing to remove an unmanaged extension volume");
            }
        }
        for (InspectContainerResponse current : containers) {
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
        }
        for (var volume : volumes) docker.removeVolumeCmd(volume.getName()).exec();
    }

    @Override
    public void uninstallInstance(String runtimeIdentity, String pluginId) {
        var containers = managedRuntimeContainers(runtimeIdentity);
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId, runtimeIdentity);
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the plugin instance before uninstalling it");
            }
        }
        var volumeResponse = docker.listVolumesCmd().exec();
        var listedVolumes = volumeResponse == null || volumeResponse.getVolumes() == null
                ? List.<com.github.dockerjava.api.command.InspectVolumeResponse>of() : volumeResponse.getVolumes();
        var volumes = listedVolumes.stream().filter(volume -> volume.getLabels() != null
                && pluginId.equals(volume.getLabels().get("io.artio.extension"))
                && runtimeIdentity.equals(volume.getLabels().get("io.artio.extension.runtime"))).toList();
        for (var volume : volumes) {
            if (!"true".equals(volume.getLabels().get("io.artio.managed"))) {
                throw new IllegalStateException("Refusing to remove an unmanaged extension volume");
            }
        }
        for (InspectContainerResponse current : containers) docker.removeContainerCmd(current.getId()).withForce(false).exec();
        for (var volume : volumes) docker.removeVolumeCmd(volume.getName()).exec();
    }

    @Override
    public void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled) {
        rotateCredentials(manifest, credentialGeneration, restartIfEnabled, Map.of());
    }

    @Override
    public void rotateCredentials(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled,
            Map<String, String> configuration) {
        var containers = managedContainers(manifest.id());
        for (InspectContainerResponse current : containers) requireManaged(current, manifest.id());
        for (InspectContainerResponse current : containers) {
            if (Boolean.TRUE.equals(current.getState().getRunning())) docker.stopContainerCmd(current.getId()).withTimeout(10).exec();
        }
        for (InspectContainerResponse current : containers) {
            requireManaged(current, manifest.id());
            docker.removeContainerCmd(current.getId()).withForce(true).exec();
        }
        if (restartIfEnabled) enable(manifest, credentialGeneration, configuration);
    }

    private void removeStoppedManagedContainer(String pluginId) {
        var containers = managedContainers(pluginId);
        for (InspectContainerResponse current : containers) {
            requireManaged(current, pluginId);
            if (Boolean.TRUE.equals(current.getState().getRunning())) {
                throw new IllegalStateException("Disable the extension before updating its image");
            }
        }
        for (InspectContainerResponse current : containers) {
            docker.removeContainerCmd(current.getId()).withForce(false).exec();
        }
    }

    @Override
    public ExtensionRuntimePort.RuntimeHealth health(String pluginId, String healthPath) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new ExtensionRuntimePort.RuntimeHealth(false, "STOPPED");
            URI endpoint = endpoint(container, safeHealthPath(healthPath));
            if (endpoint == null) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new ExtensionRuntimePort.RuntimeHealth(response.statusCode() >= 200 && response.statusCode() < 300,
                    "HTTP_" + response.statusCode());
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "NOT_INSTALLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.RuntimeHealth(false, "INTERRUPTED");
        } catch (Exception failure) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "UNAVAILABLE_" + failure.getClass().getSimpleName());
        }
    }

    @Override
    public ExtensionRuntimePort.RuntimeHealth health(PluginManifest manifest) {
        return healthInstance(manifest.id(), manifest);
    }

    @Override
    public ExtensionRuntimePort.RuntimeHealth healthInstance(String runtimeIdentity, PluginManifest manifest) {
        var serviceStatuses = new java.util.LinkedHashMap<String, String>();
        boolean healthy = true;
        for (RuntimeService service : services(manifest)) {
            var result = serviceHealth(runtimeIdentity, manifest.id(), service.id(), service.port(), service.healthPath());
            serviceStatuses.put(service.id(), result.status());
            healthy &= result.healthy();
        }
        return new ExtensionRuntimePort.RuntimeHealth(healthy, healthy ? "HEALTHY" : "DEGRADED", serviceStatuses);
    }

    @Override
    public ExtensionRuntimePort.TelemetryResponse forwardOtlp(String pluginId, String signal, byte[] payload) {
        if (!"observability.artio".equals(pluginId) || !java.util.Set.of("metrics", "traces").contains(signal)) {
            throw new IllegalArgumentException("Unsupported OTLP destination");
        }
        if (payload == null || payload.length == 0 || payload.length > 4_194_304) {
            throw new IllegalArgumentException("OTLP payload must be between 1 byte and 4 MiB");
        }
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(serviceName(pluginId, "collector")).exec();
            requireManaged(container, pluginId);
            var labels = container.getConfig().getLabels();
            if (!"collector".equals(labels.get("io.artio.extension.service"))
                    || !java.util.Arrays.asList(labels.getOrDefault("io.artio.extension.ports", "").split(",")).contains("4318")) {
                throw new IllegalStateException("The managed OTLP collector does not expose the required private receiver port");
            }
            if (!Boolean.TRUE.equals(container.getState().getRunning())) {
                return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
            }
            URI target = endpoint(container, "/v1/" + signal, 4318);
            if (target == null) return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
            HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-protobuf")
                    .header("Accept", "application/x-protobuf, application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) { body = input.readNBytes(65_537); }
            if (body.length > 65_536) return new ExtensionRuntimePort.TelemetryResponse(502, "application/json", new byte[0]);
            return new ExtensionRuntimePort.TelemetryResponse(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse("application/x-protobuf"), body);
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
        } catch (java.net.http.HttpTimeoutException timeout) {
            return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
        } catch (java.io.IOException failure) {
            return new ExtensionRuntimePort.TelemetryResponse(503, "application/json", new byte[0]);
        }
    }

    @Override
    public ExtensionRuntimePort.UiProxyResponse proxyObservability(String pluginId, String method, String path, String query) {
        return proxyObservability(pluginId, method, path, query, null, null);
    }

    @Override
    public ExtensionRuntimePort.UiProxyResponse proxyObservability(String pluginId, String method, String path, String query,
            String userId, String email) {
        if (!"observability.artio".equals(pluginId) || !("GET".equals(method) || "HEAD".equals(method))) {
            throw new IllegalArgumentException("Unsupported observability proxy request");
        }
        if (path == null || path.length() > 1024 || !path.matches("/[A-Za-z0-9_./-]*") || path.contains("..")
                || query != null && query.length() > 4096) {
            throw new IllegalArgumentException("Invalid observability proxy path");
        }
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(serviceName(pluginId, "grafana")).exec();
            requireManaged(container, pluginId);
            var labels = container.getConfig().getLabels();
            if (!"grafana".equals(labels.get("io.artio.extension.service"))
                    || !Boolean.TRUE.equals(container.getState().getRunning())) {
                return new ExtensionRuntimePort.UiProxyResponse(503, "application/json", Map.of(), new byte[0]);
            }
            URI target = endpoint(container, "/plugins/observability" + (path.equals("/") ? "/" : path), 3000);
            if (target == null) return new ExtensionRuntimePort.UiProxyResponse(503, "application/json", Map.of(), new byte[0]);
            if (query != null && !query.isBlank()) target = URI.create(target.toString() + "?" + query);
            var builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                    .header("Accept", "text/html,application/json,*/*");
            if (userId != null || email != null) {
                if (userId == null || email == null || email.isBlank() || email.length() > 254
                        || email.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Observability user identity is invalid");
                }
                String grafanaUser = "artio-" + java.util.UUID.fromString(userId);
                builder.header("X-WEBAUTH-USER", grafanaUser).header("X-WEBAUTH-EMAIL", email);
            }
            HttpRequest request = "HEAD".equals(method)
                    ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) { body = input.readNBytes(8_388_609); }
            if (body.length > 8_388_608) return new ExtensionRuntimePort.UiProxyResponse(502, "application/json", Map.of(), new byte[0]);
            var headers = new java.util.LinkedHashMap<String, String>();
            response.headers().firstValue("Cache-Control").ifPresent(value -> headers.put("Cache-Control", value));
            response.headers().firstValue("ETag").ifPresent(value -> headers.put("ETag", value));
            response.headers().firstValue("Last-Modified").ifPresent(value -> headers.put("Last-Modified", value));
            return new ExtensionRuntimePort.UiProxyResponse(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse("application/octet-stream"), headers, body);
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.UiProxyResponse(503, "application/json", Map.of(), new byte[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.UiProxyResponse(503, "application/json", Map.of(), new byte[0]);
        } catch (java.io.IOException failure) {
            return new ExtensionRuntimePort.UiProxyResponse(503, "application/json", Map.of(), new byte[0]);
        }
    }

    @Override
    public ExtensionRuntimePort.InvocationResult invoke(String pluginId, String extensionPoint, String operation, String idempotencyKey,
                                   String configurationJson, String requestJson, Duration deadline) {
        return invoke(pluginId, 1, extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
    }

    @Override
    public ExtensionRuntimePort.InvocationResult invoke(String pluginId, int credentialGeneration, String extensionPoint,
            String operation, String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        if (credentialGeneration < 1) throw new IllegalArgumentException("Credential generation must be positive");
        ExtensionRuntimePort.validateInvocation(extensionPoint, operation, idempotencyKey, configurationJson, requestJson, deadline);
        ExtensionCircuitBreaker breaker = circuits.computeIfAbsent(pluginId,
                ignored -> new ExtensionCircuitBreaker(5, Duration.ofSeconds(30)));
        try {
            breaker.acquire();
            long deadlineNanos = System.nanoTime() + deadline.toNanos();
            ExtensionRuntimePort.InvocationResult result;
            for (int attempt = 0; ; attempt++) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
                try {
                    result = invokeOnce(pluginId, credentialGeneration, extensionPoint, operation, idempotencyKey, configurationJson,
                            requestJson, Duration.ofNanos(remainingNanos));
                    break;
                } catch (ExtensionRuntimePort.ExtensionCallException failure) {
                    if (attempt >= 1 || !isRetryable(failure.statusCode())) throw failure;
                    long pauseNanos = Math.min(Duration.ofMillis(50).toNanos(), deadlineNanos - System.nanoTime());
                    if (pauseNanos <= 0) throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
                    try {
                        TimeUnit.NANOSECONDS.sleep(pauseNanos);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension invocation retry was interrupted");
                    }
                }
            }
            breaker.succeeded();
            return result;
        } catch (ExtensionRuntimePort.ExtensionCallException providerFailure) {
            if (providerFailure.statusCode() >= 500) breaker.failed();
            else breaker.succeeded();
            throw providerFailure;
        } catch (ExtensionCircuitBreaker.CircuitOpenException open) {
            throw open;
        } catch (RuntimeException failure) {
            breaker.failed();
            throw failure;
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private ExtensionRuntimePort.InvocationResult invokeOnce(String pluginId, int credentialGeneration, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, Duration deadline) {
        try {
            InspectContainerResponse container = docker.inspectContainerCmd(containerName(pluginId)).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension is stopped");
            URI endpoint = endpoint(container, "/v1/invoke");
            if (endpoint == null) throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension has no runtime network address");
            String envelope = "{\"protocolVersion\":1,\"extensionPoint\":\"" + jsonEscape(extensionPoint)
                    + "\",\"operation\":\"" + jsonEscape(operation) + "\",\"idempotencyKey\":\""
                    + jsonEscape(idempotencyKey) + "\",\"configuration\":" + configurationJson
                    + ",\"payload\":" + requestJson + "}";
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Extension invocation exceeds 1 MiB");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(deadline).header("Authorization", "Bearer " + credentials.derive(pluginId, credentialGeneration))
                    .header("Content-Type", "application/json").header("X-Artio-Extension-Api", "1")
                    .header("Idempotency-Key", idempotencyKey).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] responseBody = stream.readNBytes((int) MAX_RESPONSE_BYTES + 1);
                if (responseBody.length > MAX_RESPONSE_BYTES) throw new ExtensionRuntimePort.ExtensionProtocolException("Extension response exceeds 1 MiB");
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new ExtensionRuntimePort.ExtensionCallException(response.statusCode());
                }
                return new ExtensionRuntimePort.InvocationResult(response.statusCode(), new String(responseBody, StandardCharsets.UTF_8));
            }
        } catch (NotFoundException missing) {
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension is not installed");
        } catch (java.net.http.HttpTimeoutException timeout) {
            throw new ExtensionRuntimePort.ExtensionTimeoutException("Extension invocation exceeded its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension invocation was interrupted");
        } catch (java.io.IOException failure) {
            throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension could not be reached");
        }
    }

    private void awaitReady(String runtimeIdentity, String pluginId, String serviceId, int port, String healthPath) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String lastHealthStatus = "NOT_CHECKED";
        do {
            var health = serviceHealth(runtimeIdentity, pluginId, serviceId, port, healthPath);
            if (health.healthy()) return;
            lastHealthStatus = health.status();
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                disableInstance(runtimeIdentity, pluginId);
                throw new ExtensionRuntimePort.ExtensionUnavailableException("Extension readiness was interrupted");
            }
        } while (System.nanoTime() < deadline);
        disableInstance(runtimeIdentity, pluginId);
        throw new ExtensionRuntimePort.ExtensionUnavailableException(
                "Extension service '" + serviceId + "' did not become healthy within 30 seconds (last health check: "
                        + lastHealthStatus + ")");
    }

    private ExtensionRuntimePort.RuntimeHealth serviceHealth(String runtimeIdentity, String pluginId,
            String serviceId, int port, String healthPath) {
        try {
            String name = serviceId.equals("main") ? containerName(runtimeIdentity) : serviceName(runtimeIdentity, serviceId);
            InspectContainerResponse container = docker.inspectContainerCmd(name).exec();
            requireManaged(container, pluginId, runtimeIdentity);
            if (!Boolean.TRUE.equals(container.getState().getRunning())) return new ExtensionRuntimePort.RuntimeHealth(false, "STOPPED");
            var exposed = container.getConfig() == null ? null : container.getConfig().getExposedPorts();
            if (exposed == null || java.util.Arrays.stream(exposed).noneMatch(value -> value.getPort() == port)) {
                return new ExtensionRuntimePort.RuntimeHealth(false, "NO_EXPOSED_PORT");
            }
            URI address = endpoint(container, safeHealthPath(healthPath), port);
            if (address == null) return new ExtensionRuntimePort.RuntimeHealth(false, "NO_NETWORK_ADDRESS");
            var response = http.send(HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return new ExtensionRuntimePort.RuntimeHealth(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP_" + response.statusCode());
        } catch (NotFoundException missing) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "NOT_INSTALLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExtensionRuntimePort.RuntimeHealth(false, "INTERRUPTED");
        } catch (Exception failure) {
            return new ExtensionRuntimePort.RuntimeHealth(false, "UNAVAILABLE_" + failure.getClass().getSimpleName());
        }
    }

    private URI endpoint(InspectContainerResponse container, String path) {
        return endpoint(container, path, port);
    }

    private URI endpoint(InspectContainerResponse container, String path, int servicePort) {
        if (hostLoopbackTestAccess) {
            var bindings = container.getNetworkSettings() == null || container.getNetworkSettings().getPorts() == null
                    ? null : container.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(servicePort));
            if (bindings == null || bindings.length == 0 || bindings[0].getHostPortSpec() == null) return null;
            return URI.create("http://127.0.0.1:" + bindings[0].getHostPortSpec() + path);
        }
        var networks = container.getNetworkSettings().getNetworks();
        var networkInfo = networks == null ? null : networks.get(network);
        String address = networkInfo == null ? null : networkInfo.getIpAddress();
        return address == null || address.isBlank() ? null : URI.create("http://" + address + ":" + servicePort + path);
    }

    private java.util.List<InspectContainerResponse> managedContainers(String pluginId) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getLabels() != null && pluginId.equals(container.getLabels().get("io.artio.extension")))
                .map(container -> docker.inspectContainerCmd(container.getId()).exec()).toList();
    }

    private java.util.List<InspectContainerResponse> managedRuntimeContainers(String runtimeIdentity) {
        return docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getLabels() != null
                        && runtimeIdentity.equals(container.getLabels().get("io.artio.extension.runtime")))
                .map(container -> docker.inspectContainerCmd(container.getId()).exec()).toList();
    }

    private static void requireManaged(InspectContainerResponse container, String pluginId) {
        var labels = container.getConfig() == null ? null : container.getConfig().getLabels();
        if (labels == null || !"true".equals(labels.get("io.artio.managed")) || !pluginId.equals(labels.get("io.artio.extension")))
            throw new IllegalStateException("Refusing to manage an unmanaged extension container");
    }

    private static void requireManaged(InspectContainerResponse container, String pluginId, String runtimeIdentity) {
        var labels = container.getConfig() == null ? null : container.getConfig().getLabels();
        String recordedRuntimeIdentity = labels == null ? null : labels.get("io.artio.extension.runtime");
        if (labels == null || !"true".equals(labels.get("io.artio.managed"))
                || !pluginId.equals(labels.get("io.artio.extension"))
                || !(runtimeIdentity.equals(recordedRuntimeIdentity)
                        || runtimeIdentity.equals(pluginId) && recordedRuntimeIdentity == null)) {
            throw new IllegalStateException("Refusing to manage a container outside the requested plugin instance");
        }
    }

    private java.util.List<RuntimeService> services(PluginManifest manifest) {
        var result = new java.util.ArrayList<RuntimeService>();
        result.add(new RuntimeService("main", manifest.image(), port, manifest.healthPath(), true,
                manifest.configurationBindings(), manifest.secretFileBindings(), manifest.persistentPaths(), List.of(), 0, 268_435_456L));
        for (var service : manifest.services()) result.add(new RuntimeService(service.id(), service.image(), service.port(),
                service.healthPath(), false, service.configurationBindings(), service.secretFileBindings(), service.persistentPaths(), service.internalPorts(), 0,
                (service.memoryLimitMb() == null ? 256L : service.memoryLimitMb().longValue()) * 1_048_576L));
        return java.util.List.copyOf(result);
    }

    private java.util.List<RuntimeService> servicesInStartupOrder(PluginManifest manifest) {
        var byId = new java.util.LinkedHashMap<String, RuntimeService>();
        for (RuntimeService service : services(manifest)) byId.put(service.id(), service);
        var dependencies = new java.util.LinkedHashMap<String, java.util.Set<String>>();
        dependencies.put("main", java.util.Set.of());
        manifest.services().forEach(service -> dependencies.put(service.id(), new java.util.LinkedHashSet<>(service.dependsOn())));
        var ordered = new java.util.ArrayList<RuntimeService>();
        var visited = new java.util.HashSet<String>();
        var visiting = new java.util.HashSet<String>();
        for (String id : byId.keySet()) visitService(id, byId, dependencies, visited, visiting, ordered);
        var indexed = new java.util.ArrayList<RuntimeService>();
        for (int index = 0; index < ordered.size(); index++) {
            RuntimeService service = ordered.get(index);
            indexed.add(new RuntimeService(service.id(), service.image(), service.port(), service.healthPath(),
                    service.primary(), service.configurationBindings(), service.secretFileBindings(), service.persistentPaths(), service.internalPorts(), index,
                    service.memoryBytes()));
        }
        return java.util.List.copyOf(indexed);
    }

    private static int startupOrder(InspectContainerResponse container) {
        var labels = container.getConfig() == null ? null : container.getConfig().getLabels();
        try { return Integer.parseInt(labels == null ? null : labels.get("io.artio.extension.startup-order")); }
        catch (RuntimeException legacyContainer) { return -1; }
    }

    private static void visitService(String id, Map<String, RuntimeService> services,
            Map<String, java.util.Set<String>> dependencies, java.util.Set<String> visited,
            java.util.Set<String> visiting, java.util.List<RuntimeService> ordered) {
        if (visited.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Bundle service startup dependencies contain a cycle");
        for (String dependency : dependencies.getOrDefault(id, java.util.Set.of())) {
            if (!services.containsKey(dependency)) throw new IllegalArgumentException("Bundle service depends on an unknown service");
            visitService(dependency, services, dependencies, visited, visiting, ordered);
        }
        visiting.remove(id);
        visited.add(id);
        ordered.add(services.get(id));
    }

    static String serviceName(String pluginId, String serviceId) {
        if (serviceId == null || !serviceId.matches("[a-z][a-z0-9-]{0,30}")) throw new IllegalArgumentException("Invalid plugin service id");
        if (serviceId.equals("main")) return containerName(pluginId);
        String primary = containerName(pluginId);
        return "artio-svc-" + primary.substring(primary.length() - 12) + "-" + serviceId;
    }

    private record RuntimeService(String id, String image, int port, String healthPath, boolean primary,
                                  Map<String, String> configurationBindings, Map<String, String> secretFileBindings,
                                  java.util.List<String> persistentPaths,
                                  java.util.List<Integer> internalPorts, int startupOrder, long memoryBytes) { }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String containerName(String pluginId) {
        if (pluginId == null || pluginId.length() > 160 || !pluginId.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid plugin id for Docker runtime");
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(pluginId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String suffix = java.util.HexFormat.of().formatHex(hash, 0, 6);
            String slug = pluginId.replace('.', '-');
            if (slug.length() > 42) slug = slug.substring(0, 42);
            return "artio-ext-" + slug + "-" + suffix;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String validateRuntimeIdentity(String runtimeIdentity) {
        if (runtimeIdentity == null || runtimeIdentity.length() > 160 || !runtimeIdentity.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid plugin runtime identity");
        }
        return runtimeIdentity;
    }

    static String safeHealthPath(String path) {
        if (path == null || !path.matches("/[A-Za-z0-9/_-]{0,120}") || path.contains("..")) {
            throw new IllegalArgumentException("Plugin health path is invalid");
        }
        return path;
    }

    private static void requirePinnedImage(String image) {
        if (image == null || !image.matches("[a-zA-Z0-9][a-zA-Z0-9./:_-]*@sha256:[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Extension runtime requires an immutable sha256 image digest");
        }
    }

}
