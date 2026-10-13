package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises clean installation and the supported V9-to-current schema upgrade against PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void installsCleanlyAndUpgradesExistingV9UserDataThroughCurrentSchema() throws Exception {
        Flyway cleanInstall = flyway(POSTGRES.getJdbcUrl()).load();
        var cleanResult = cleanInstall.migrate();
        assertThat(cleanResult.migrationsExecuted).isEqualTo(15);
        assertThat(cleanInstall.info().current().getVersion().getVersion()).isEqualTo("15");
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
        String upgradeDatabase = "artio_upgrade";
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + upgradeDatabase);
        }

        String upgradeUrl = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + upgradeDatabase);
        Flyway beforeUpgrade = flyway(upgradeUrl).target(MigrationVersion.fromVersion("9")).load();
        beforeUpgrade.migrate();

        UUID userId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) "
                                + "VALUES (?, ?, ?, ?, ?, now())")) {
            statement.setObject(1, userId);
            statement.setString(2, "upgrade@example.test");
            statement.setString(3, "test-hash");
            statement.setString(4, "Upgrade");
            statement.setString(5, "Test");
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }

        Flyway afterUpgrade = flyway(upgradeUrl).load();
        assertThat(afterUpgrade.migrate().migrationsExecuted).isEqualTo(6);

        try (var connection = DriverManager.getConnection(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT u.email, u.tenant_id, m.role FROM users u "
                                + "JOIN tenant_memberships m ON m.user_id=u.id AND m.tenant_id=u.tenant_id "
                                + "WHERE u.id=?")) {
            statement.setObject(1, userId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("email")).isEqualTo("upgrade@example.test");
                assertThat(result.getObject("tenant_id")).isNotNull();
                assertThat(result.getString("role")).isEqualTo("CUSTOMER");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }
}
