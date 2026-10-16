package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Live cross-repository gate: Core fetches and enforces the real Marketplace signed feed. */
@Testcontainers(disabledWithoutDocker = true)
class MarketplaceAdvisoryAcceptanceIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void consumesAndPersistsTheLiveMarketplaceSecurityRevocation() {
        String baseUrl = System.getenv("ARTIO_MARKETPLACE_BASE_URL");
        String trustedKeys = System.getenv("ARTIO_MARKETPLACE_ADVISORY_TRUSTED_KEYS");
        assumeTrue(baseUrl != null && !baseUrl.isBlank() && trustedKeys != null && !trustedKeys.isBlank(),
                "Configure the live Marketplace origin and independent advisory public key for cross-repository acceptance");

        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        ObjectMapper mapper = new ObjectMapper();
        var client = new MarketplaceCatalogClient(mapper, baseUrl, new OfficialReleaseDescriptorVerifier(""));
        var verifier = new SignedMarketplaceAdvisoryVerifier(trustedKeys);
        var store = new MarketplaceAdvisoryStore(jdbc, mapper);
        var worker = new MarketplaceAdvisorySyncWorker(client, verifier, store);

        worker.syncSafely();

        assertThat(store.status().sequence()).isPositive();
        assertThat(store.status().lastErrorCode()).isNull();
        String pluginId = System.getenv().getOrDefault("ARTIO_MARKETPLACE_EXPECTED_BANNED_PLUGIN_ID", "marketplace.reference");
        String version = System.getenv().getOrDefault("ARTIO_MARKETPLACE_EXPECTED_BANNED_PLUGIN_VERSION", "1.2.9");
        var release = jdbc.query("SELECT security_status,banned_image_digests::text FROM extension_marketplace_release_advisories WHERE plugin_id=? AND version=?",
                rs -> rs.next() ? new Release(rs.getString(1), rs.getString(2)) : null, pluginId, version);
        assertThat(release).isNotNull();
        assertThat(release.securityStatus()).isEqualTo("BANNED");
        assertThat(release.bannedDigests()).contains("sha256:");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_marketplace_banned_artifacts", Integer.class)).isPositive();
    }

    private record Release(String securityStatus, String bannedDigests) {}
}
