package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.artio.extensions.PluginManifest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

class DockerExtensionRuntimeSecretFileTests {
    @Test
    void archivesOnlyDeclaredSecretFilesWithPrivateModeAndPluginOwnership() throws Exception {
        var schema = Map.of("accessKey", new PluginManifest.ConfigField("secret", true, true, "Access key",
                null, null, List.of(), null, null, null, null));
        byte[] archive = DockerExtensionRuntime.secretFilesArchive(Map.of("/run/secrets/access-key", "accessKey"),
                schema, Map.of("accessKey", "generated-secret-value"));

        try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(archive))) {
            var entry = tar.getNextTarEntry();
            assertThat(entry.getName()).isEqualTo("access-key");
            assertThat(entry.getMode()).isEqualTo(0600);
            assertThat(entry.getLongUserId()).isEqualTo(10001);
            assertThat(entry.getLongGroupId()).isEqualTo(10001);
            assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("generated-secret-value");
            assertThat(tar.getNextTarEntry()).isNull();
        }
    }

    @Test
    void rejectsSecretFilesOutsideTmpfsAndMissingRequiredSecrets() {
        var schema = Map.of("accessKey", new PluginManifest.ConfigField("secret", true, true, "Access key",
                null, null, List.of(), null, null, null, null));
        assertThatThrownBy(() -> DockerExtensionRuntime.secretFilesArchive(Map.of("/tmp/access-key", "accessKey"),
                schema, Map.of("accessKey", "value"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DockerExtensionRuntime.secretFilesArchive(Map.of("/run/secrets/access-key", "accessKey"),
                schema, Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
