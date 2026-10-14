package io.artio.api.distributions;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records the availability and lifecycle of each distribution-recommended plugin without inventing releases. */
@Service
public class DistributionPluginProvisioningService {
    private final RegionalDistributionCatalog catalog;
    private final JdbcTemplate jdbc;

    public DistributionPluginProvisioningService(RegionalDistributionCatalog catalog, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    @Transactional
    public List<ProvisioningItem> initialize(String distributionId) {
        return reconcile(distributionId);
    }

    @Transactional(readOnly = true)
    public List<ProvisioningItem> currentInstallation() {
        return jdbc.query("SELECT distribution_id,plugin_id,optional,provisioning_status,lifecycle_state,reason,last_checked_at FROM artio_distribution_plugin_provisioning ORDER BY plugin_id",
                (rs,row)->new ProvisioningItem(rs.getString("distribution_id"),rs.getString("plugin_id"),rs.getBoolean("optional"),
                        rs.getString("reason"),rs.getString("provisioning_status"),rs.getString("lifecycle_state"),rs.getTimestamp("last_checked_at").toInstant()));
    }

    @Transactional
    public List<ProvisioningItem> reconcile(String distributionId) {
        RegionalDistribution distribution = catalog.find(distributionId);
        return distribution.pluginRecommendations().stream().map(recommendation -> record(distribution, recommendation)).toList();
    }

    @Transactional(readOnly = true)
    public List<CatalogItem> catalog(String countryCode) {
        if (countryCode != null && (!countryCode.matches("[A-Z]{2}")
                || !Set.of(Locale.getISOCountries()).contains(countryCode))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "country must be an ISO 3166-1 alpha-2 code");
        }
        return catalog.findAll().stream()
                .filter(distribution -> countryCode == null || distribution.countryCode().equals(countryCode))
                .flatMap(distribution -> distribution.pluginRecommendations().stream().map(recommendation -> {
                    List<RegisteredPlugin> registered = jdbc.query(
                            "SELECT manifest->>'name',manifest->>'version',lifecycle_state FROM extension_plugins WHERE plugin_id=?",
                            (rs, row) -> new RegisteredPlugin(rs.getString(1), rs.getString(2), rs.getString(3)),
                            recommendation.pluginId());
                    RegisteredPlugin plugin = registered.isEmpty() ? null : registered.getFirst();
                    return new CatalogItem(distribution.id(), distribution.countryCode(), recommendation.pluginId(),
                            plugin == null ? recommendation.name() : plugin.name(), recommendation.description(),
                            recommendation.extensionPoints(), recommendation.optional(), recommendation.reason(),
                            plugin != null, plugin == null ? null : plugin.version(),
                            plugin == null ? null : plugin.lifecycleState(),
                            plugin == null ? "WAITING_FOR_RELEASE" : statusFor(plugin.lifecycleState()));
                }))
                .toList();
    }

    @Transactional
    public void refreshPlugin(String pluginId) {
        String distributionId=jdbc.query("SELECT distribution_id FROM artio_installation_settings WHERE singleton_id=true",
                rs->rs.next()?rs.getString(1):null);
        if(distributionId==null)return;
        RegionalDistribution distribution=catalog.find(distributionId);
        distribution.pluginRecommendations().stream().filter(recommendation->recommendation.pluginId().equals(pluginId))
                .findFirst().ifPresent(recommendation->record(distribution,recommendation));
    }

    private ProvisioningItem record(RegionalDistribution distribution, RegionalDistribution.PluginRecommendation recommendation) {
        List<String> lifecycle = jdbc.query("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id=?",
                (rs, row) -> rs.getString(1), recommendation.pluginId());
        String lifecycleState = lifecycle.isEmpty() ? null : lifecycle.getFirst();
        String status = statusFor(lifecycleState);
        Instant checkedAt=Instant.now();
        jdbc.update("INSERT INTO artio_distribution_plugin_provisioning(distribution_id,plugin_id,optional,provisioning_status,lifecycle_state,reason,last_checked_at) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT(distribution_id,plugin_id) DO UPDATE SET optional=EXCLUDED.optional,provisioning_status=EXCLUDED.provisioning_status,lifecycle_state=EXCLUDED.lifecycle_state,reason=EXCLUDED.reason,last_checked_at=EXCLUDED.last_checked_at",
                distribution.id(), recommendation.pluginId(), recommendation.optional(), status, lifecycleState, recommendation.reason(), java.sql.Timestamp.from(checkedAt));
        return new ProvisioningItem(distribution.id(), recommendation.pluginId(), recommendation.optional(),
                recommendation.reason(), status, lifecycleState, checkedAt);
    }

    static String statusFor(String lifecycleState) {
        if (lifecycleState == null) return "WAITING_FOR_RELEASE";
        return switch (lifecycleState) {
            case "VALIDATED" -> "CONFIGURATION_REQUIRED";
            case "CONFIGURED" -> "READY_TO_INSTALL";
            case "FAILED" -> "RETRY_REQUIRED";
            case "INSTALLED", "DISABLED" -> "INSTALLED_DISABLED";
            case "ENABLED" -> "ACTIVE";
            case "UNINSTALLED" -> "UNINSTALLED_BY_ADMIN";
            default -> "LIFECYCLE_IN_PROGRESS";
        };
    }

    public record ProvisioningItem(String distributionId, String pluginId, boolean optional, String reason,
                                  String status, String lifecycleState, Instant lastCheckedAt) { }
    public record CatalogItem(String distributionId, String countryCode, String pluginId, String name,
            String description, List<String> extensionPoints, boolean optional, String reason,
            boolean releaseRegistered, String version, String lifecycleState, String status) { }
    private record RegisteredPlugin(String name, String version, String lifecycleState) { }
}
