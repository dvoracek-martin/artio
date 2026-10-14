package io.artio.api.distributions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DistributionPluginProvisioningServiceTests {
    @Test
    void reportsPublicationConfigurationInstallRetryAndActivationStatesTruthfully() {
        assertThat(DistributionPluginProvisioningService.statusFor(null)).isEqualTo("WAITING_FOR_RELEASE");
        assertThat(DistributionPluginProvisioningService.statusFor("VALIDATED")).isEqualTo("CONFIGURATION_REQUIRED");
        assertThat(DistributionPluginProvisioningService.statusFor("CONFIGURED")).isEqualTo("READY_TO_INSTALL");
        assertThat(DistributionPluginProvisioningService.statusFor("FAILED")).isEqualTo("RETRY_REQUIRED");
        assertThat(DistributionPluginProvisioningService.statusFor("INSTALLED")).isEqualTo("INSTALLED_DISABLED");
        assertThat(DistributionPluginProvisioningService.statusFor("DISABLED")).isEqualTo("INSTALLED_DISABLED");
        assertThat(DistributionPluginProvisioningService.statusFor("ENABLED")).isEqualTo("ACTIVE");
        assertThat(DistributionPluginProvisioningService.statusFor("UNINSTALLED")).isEqualTo("UNINSTALLED_BY_ADMIN");
    }
}
