package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
        assertThat(cleanResult.migrationsExecuted).isEqualTo(22);
        assertThat(cleanInstall.info().current().getVersion().getVersion()).isEqualTo("22");
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
                var result = statement.executeQuery("SELECT to_regclass('payment_webhook_receipts')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("payment_webhook_receipts");
        }
        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT to_regclass('notification_delivery_outbox')")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("notification_delivery_outbox");
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
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }
}
