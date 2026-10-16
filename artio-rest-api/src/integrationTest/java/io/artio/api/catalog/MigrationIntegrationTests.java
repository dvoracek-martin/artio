package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.api.payments.PaymentWebhookReceiptRetentionWorker;
import io.artio.api.extensions.MarketplaceAdvisoryStore;
import io.artio.api.extensions.SignedMarketplaceAdvisoryVerifier;

/** Exercises the supported clean installation against PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void sharedCatalogWriteLockBlocksExclusiveIndexRebuildAcrossConnections() throws Exception {
        try (var writer = POSTGRES.createConnection(""); var rebuild = POSTGRES.createConnection("")) {
            writer.setAutoCommit(false);
            rebuild.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                statement.executeQuery("SELECT pg_advisory_xact_lock_shared(" + CatalogSearchIndexLock.LOCK_NAMESPACE
                        + ", " + CatalogSearchIndexLock.LOCK_ID + ")").close();
            }
            try (var statement = rebuild.createStatement();
                    var result = statement.executeQuery("SELECT pg_try_advisory_xact_lock(" + CatalogSearchIndexLock.LOCK_NAMESPACE
                            + ", " + CatalogSearchIndexLock.LOCK_ID + ")")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isFalse();
            }
            writer.rollback();
            try (var statement = rebuild.createStatement();
                    var result = statement.executeQuery("SELECT pg_try_advisory_xact_lock(" + CatalogSearchIndexLock.LOCK_NAMESPACE
                            + ", " + CatalogSearchIndexLock.LOCK_ID + ")")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
            rebuild.rollback();
        }
    }

    @Test
    void installsCleanlyThroughCurrentSchemaWithoutTenantModel() throws Exception {
        Flyway cleanInstall = flyway(POSTGRES.getJdbcUrl()).load();
        var cleanResult = cleanInstall.migrate();
        assertThat(cleanResult.migrationsExecuted).isEqualTo(1);
        assertThat(cleanInstall.info().current().getVersion().getVersion()).isEqualTo("1");
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT valid_until FROM pricing_tax_rule WHERE id='00000000-0000-0000-0000-000000000038'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp(1).toInstant()).isEqualTo(java.time.Instant.parse("2028-01-01T00:00:00Z"));
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('inventory_low_stock_policy')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("inventory_low_stock_policy");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='catalog_product' AND column_name IN ('seo_slug','seo_title','seo_description')")) {
            var columns = new java.util.ArrayList<String>();
            while (result.next()) columns.add(result.getString(1));
            assertThat(columns).containsExactlyInAnyOrder("seo_slug", "seo_title", "seo_description");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='catalog_product' AND column_name='archived_at'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("archived_at");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='event_outbox' AND column_name='dead_at'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='artio_installation_settings' AND column_name IN ('store_name','available_languages','external_base_url','distribution_id')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(4);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('catalog_product_translations')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("catalog_product_translations");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='inventory_reservation' AND column_name='expires_at'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='orders' AND column_name IN ('shipping_country','market','tax_jurisdiction','tax_snapshot')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(4);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('artio_password_reset_tokens')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("artio_password_reset_tokens");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='users' AND column_name='token_version'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='orders' AND column_name='idempotency_key'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('extension_plugins')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("extension_plugins");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('extension_plugin_releases')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("extension_plugin_releases");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name='extension_plugins' AND column_name IN ('credential_generation','rotation_was_enabled','rotation_previous_state')")) {
            var columns = new java.util.ArrayList<String>();
            while (result.next()) columns.add(result.getString(1));
            assertThat(columns).containsExactlyInAnyOrder("credential_generation", "rotation_was_enabled", "rotation_previous_state");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('payment_webhook_receipts')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("payment_webhook_receipts");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('payment_refunds'),to_regclass('order_returns'),to_regclass('order_return_items'),to_regclass('order_return_events'),to_regclass('order_timeline_events'),to_regclass('shipment_tracking_events'),to_regclass('order_shipments'),to_regclass('order_shipment_items')")) {
            assertThat(result.next()).isTrue();
            assertThat(java.util.List.of(result.getString(1),result.getString(2),result.getString(3),result.getString(4),result.getString(5),result.getString(6),result.getString(7),result.getString(8)))
                    .containsExactly("payment_refunds","order_returns","order_return_items","order_return_events","order_timeline_events","shipment_tracking_events","order_shipments","order_shipment_items");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='inventory_reservation' AND column_name IN ('allocated_quantity','released_quantity')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='loyalty_transactions' AND column_name IN ('refund_id','idempotency_key','reason')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('notification_delivery_outbox')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("notification_delivery_outbox");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('event_inbox')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("event_inbox");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('extension_plugin_instances'), to_regclass('extension_capability_bindings'), to_regclass('extension_operation_attempts'), to_regclass('extension_event_subscriptions'), to_regclass('extension_event_deliveries')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("extension_plugin_instances");
            assertThat(result.getString(2)).isEqualTo("extension_capability_bindings");
            assertThat(result.getString(3)).isEqualTo("extension_operation_attempts");
            assertThat(result.getString(4)).isEqualTo("extension_event_subscriptions");
            assertThat(result.getString(5)).isEqualTo("extension_event_deliveries");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='pricing_price' AND column_name='prices_include_tax'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('tenants'), to_regclass('tenant_memberships')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isNull();
            assertThat(result.getString(2)).isNull();
        }
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String prefix = "retention-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('STRIPE',?,'completed',now()-interval '120 days',now()-interval '120 days')", prefix + "-old");
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('STRIPE',?,'completed',now()-interval '5 days',now()-interval '5 days')", prefix + "-recent");
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at) VALUES ('STRIPE',?,'pending',now()-interval '120 days')", prefix + "-unprocessed");

        var worker = new PaymentWebhookReceiptRetentionWorker(jdbc, 90);
        assertThat(worker.removeExpiredReceipts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_webhook_receipts WHERE event_id LIKE ?", Integer.class, prefix + "%"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_webhook_receipts WHERE event_id=? AND processed_at IS NULL", Integer.class, prefix + "-unprocessed"))
                .isEqualTo(1);
    }

    @Test
    void persistsMonotonicAdvisoriesAndNeverRemovesAnExactDigestBan() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway cleanInstall = flyway(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load();
            cleanInstall.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            var store = new MarketplaceAdvisoryStore(jdbc, new ObjectMapper());
            String imageDigest = "sha256:" + "b".repeat(64);
            var first = advisorySnapshot(1, "a".repeat(64), List.of(imageDigest), "BANNED", "BANNED");

            assertThat(store.apply(first).changed()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_marketplace_banned_artifacts WHERE image_digest=?",
                    Integer.class, imageDigest)).isEqualTo(1);

            var later = advisorySnapshot(2, "c".repeat(64), List.of(), "SUPPORTED", "ALLOWED");
            assertThat(store.apply(later).sequence()).isEqualTo(2);
            assertThat(store.status().sequence()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_marketplace_banned_artifacts WHERE image_digest=?",
                    Integer.class, imageDigest)).isEqualTo(1);
            assertThatThrownBy(() -> store.apply(advisorySnapshot(2, "d".repeat(64), List.of(), "SUPPORTED", "ALLOWED")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("different content");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM extension_marketplace_banned_artifacts WHERE image_digest=?", imageDigest))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);

            String manifestJson = new ObjectMapper().writeValueAsString(Map.of(
                    "manifestVersion", "1.0", "id", "security.plugin", "name", "Security plugin", "vendor", "Artio",
                    "version", "1.0.0", "description", "test", "minimumCoreVersion", "1.0.0", "maximumCoreVersion", "9.0.0",
                    "image", "registry.example/public/plugins/security@" + imageDigest));
            jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES ('security.plugin',?::jsonb,?,'ENABLED')",
                    manifestJson, "registry.example/public/plugins/security@" + imageDigest);
            var runtime = mock(ExtensionRuntimePort.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<ExtensionRuntimePort> runtimeProvider = mock(ObjectProvider.class);
            when(runtimeProvider.getIfAvailable()).thenReturn(runtime);
            var containment = new io.artio.api.extensions.ExtensionSecurityContainmentWorker(jdbc, new ObjectMapper(), runtimeProvider);
            containment.reconcile();
            verify(runtime).disable("security.plugin");
            assertThat(jdbc.queryForObject("SELECT containment_status FROM extension_security_blocked_targets WHERE plugin_id='security.plugin'", String.class))
                    .isEqualTo("STOPPED");
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id='security.plugin'", String.class))
                    .isEqualTo("ENABLED");

            jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES ('security.failure',?::jsonb,?,'ENABLED')",
                    manifestJson.replace("security.plugin", "security.failure"), "registry.example/public/plugins/security@" + imageDigest);
            doThrow(new IllegalStateException("runtime unavailable")).when(runtime).disable("security.failure");
            containment.reconcile();
            assertThat(jdbc.queryForObject("SELECT containment_status FROM extension_security_blocked_targets WHERE plugin_id='security.failure'", String.class))
                    .isEqualTo("STOP_FAILED");
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id='security.failure'", String.class))
                    .isEqualTo("ENABLED");
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    private static FluentConfiguration flyway(String url, String username, String password) {
        return Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration");
    }

    private static SignedMarketplaceAdvisoryVerifier.VerifiedSnapshot advisorySnapshot(
            long sequence, String payloadHash, List<String> banned, String status, String securityStatus) {
        var advisory = new SignedMarketplaceAdvisoryVerifier.Advisory(
                java.util.UUID.fromString("a16974a8-e9ad-405a-8418-a0e1dd23c135"), "shipping.demo", "1.2.3",
                "sha256:" + "a".repeat(64), banned.isEmpty() ? List.of("sha256:" + "b".repeat(64)) : banned,
                banned, "SUPPORTED", securityStatus, status, "INFO", securityStatus.equals("BANNED") ? "SECURITY_REVOKED" : "SUPPORTED_RELEASE",
                java.util.Map.of("en", "Release status."), java.time.Instant.parse("2026-10-16T12:00:00Z"), sequence);
        return new SignedMarketplaceAdvisoryVerifier.VerifiedSnapshot(sequence,
                java.time.Instant.parse("2026-10-16T12:00:00Z"), java.time.Instant.parse("2026-10-17T12:00:00Z"),
                payloadHash, List.of(advisory));
    }

}
