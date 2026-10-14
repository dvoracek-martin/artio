package io.artio.api.distributions;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SUPERADMIN view of installation-specific optional plugin readiness. */
@RestController
@RequestMapping("/api/admin/system/distribution/plugins")
public class DistributionPluginProvisioningController {
    private final DistributionPluginProvisioningService provisioning;

    public DistributionPluginProvisioningController(DistributionPluginProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @GetMapping
    public List<DistributionPluginProvisioningService.ProvisioningItem> currentInstallation() {
        return provisioning.currentInstallation();
    }

    @PostMapping("/refresh")
    public List<DistributionPluginProvisioningService.ProvisioningItem> refresh() {
        var current=provisioning.currentInstallation();
        if(current.isEmpty())return current;
        return provisioning.reconcile(current.getFirst().distributionId());
    }
}
