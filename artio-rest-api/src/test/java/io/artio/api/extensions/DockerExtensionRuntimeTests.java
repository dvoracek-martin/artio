package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DockerExtensionRuntimeTests {
    @Test
    void containerNamesAreSafeBoundedAndCollisionResistant() {
        String dotted = DockerExtensionRuntime.containerName("payments.stripe");
        String dashed = DockerExtensionRuntime.containerName("payments-stripe");
        assertThat(dotted).startsWith("artio-ext-payments-stripe-").hasSizeLessThanOrEqualTo(63);
        assertThat(dashed).isNotEqualTo(dotted);
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.containerName("../host"));
    }

    @Test
    void invocationPayloadMustFitWithinOneMiB() {
        DockerExtensionRuntime.validateInvocationSize(new byte[] { 1 });
        DockerExtensionRuntime.validateInvocationSize(new byte[1_048_576]);
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.validateInvocationSize(new byte[1_048_577]));
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.validateInvocationSize(new byte[0]));
    }

    @Test
    void containersReceiveOnlyTheRestrictedRuntimePolicy() {
        var host = DockerExtensionRuntime.restrictedHostConfig("artio-extensions");
        assertThat(host.getNetworkMode()).isEqualTo("artio-extensions");
        assertThat(host.getMemory()).isEqualTo(268_435_456L);
        assertThat(host.getNanoCPUs()).isEqualTo(1_000_000_000L);
        assertThat(host.getPidsLimit()).isEqualTo(128L);
        assertThat(host.getReadonlyRootfs()).isTrue();
        assertThat(host.getPortBindings()).isNull();
        assertThat(host.getBinds()).isEmpty();
        assertThat(host.getCapDrop()).containsExactly(com.github.dockerjava.api.model.Capability.ALL);
        assertThat(host.getSecurityOpts()).contains("no-new-privileges:true");
        assertThat(host.getTmpFs()).containsEntry("/tmp", "rw,noexec,nosuid,size=16m");
    }

    @Test
    void invocationEnvelopeHasStrictBoundsAndStableRequestIdentity() {
        DockerExtensionRuntime.validateInvocation("payment.provider.v1", "authorize", "00000000-0000-0000-0000-000000000001",
                "{\"amount\":100}", java.time.Duration.ofSeconds(5));
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.validateInvocation("payment.provider.v1",
                "authorize", "invalid", "{}", java.time.Duration.ofSeconds(5)));
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.validateInvocation("payment.provider.v1",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", java.time.Duration.ofSeconds(31)));
        assertThatIllegalArgumentException().isThrownBy(() -> DockerExtensionRuntime.validateInvocation("../../host",
                "authorize", "00000000-0000-0000-0000-000000000001", "{}", java.time.Duration.ofSeconds(5)));
    }

    @Test
    void extensionCredentialsAreStableAndScopedToThePlugin() {
        var encryption = new io.artio.api.security.EncryptionService("unit-test-key-material-that-is-at-least-32-bytes");
        String token = encryption.deriveExtensionCredential("payments.example");
        assertThat(token).isEqualTo(encryption.deriveExtensionCredential("payments.example"));
        assertThat(token).isNotEqualTo(encryption.deriveExtensionCredential("shipping.example"));
        assertThat(token).hasSize(43);
    }
}
