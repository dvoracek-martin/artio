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

/** Exercises both the fresh-install and the latest pre-V10 upgrade paths against PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void installsCleanlyAndUpgradesExistingV9UserDataThroughV10() throws Exception {
        Flyway cleanInstall = flyway(POSTGRES.getJdbcUrl()).load();
        var cleanResult = cleanInstall.migrate();
        assertThat(cleanResult.migrationsExecuted).isEqualTo(10);
        assertThat(cleanInstall.info().current().getVersion().getVersion()).isEqualTo("10");

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
        assertThat(afterUpgrade.migrate().migrationsExecuted).isEqualTo(1);

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
