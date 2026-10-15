package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import io.artio.api.payments.PaymentWebhookReceiptRetentionWorker;

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
        assertThat(cleanResult.migrationsExecuted).isEqualTo(9);
        assertThat(cleanInstall.info().current().getVersion().getVersion()).isEqualTo("34");
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

    private static FluentConfiguration flyway(String url) {
        return Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
    }

    @Test
    void upgradesV30ToPersistentLicenseIdentityWithoutChangingExistingCatalogData() {
        try (var database = new PostgreSQLContainer<>("postgres:16-alpine")) {
            database.start();
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").target("30").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            var product = java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO catalog_product(id,name,sku,active,created_at,updated_at) VALUES (?,'Upgrade fixture','UPGRADE-FIXTURE',true,now(),now())", product);
            var user = java.util.UUID.randomUUID();
            var existingOrder = java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,created_at) VALUES (?,'upgrade@example.test','fixture-hash','Upgrade','Fixture',now())", user);
            String insertOrder = "INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) VALUES (?,?,'PAID',100,0,0,100,'CHF','Fixture','Fixture',now(),'CH','CH','CH','{}',?)";
            jdbc.update(insertOrder, existingOrder, user, existingOrder.toString());
            var upgrade = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
            var identity = jdbc.queryForObject("SELECT installation_id FROM artio_license_state", java.util.UUID.class);
            assertThat(identity).isNotNull();
            assertThat(jdbc.queryForObject("SELECT name FROM catalog_product WHERE id=?", String.class, product)).isEqualTo("Upgrade fixture");
            assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, existingOrder)).isTrue();
            var newOrder = java.util.UUID.randomUUID();
            jdbc.update(insertOrder, newOrder, user, newOrder.toString());
            assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, newOrder)).isFalse();
            io.artio.api.enterprise.LoyaltyAccounting.earnForOrder(jdbc, user, existingOrder, new java.math.BigDecimal("100"));
            io.artio.api.enterprise.LoyaltyAccounting.earnForOrder(jdbc, user, newOrder, new java.math.BigDecimal("100"));
            assertThat(jdbc.queryForObject("SELECT points FROM loyalty_accounts WHERE user_id=?", Long.class, user)).isEqualTo(100L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_transactions WHERE user_id=? AND kind='EARN'", Integer.class, user)).isEqualTo(1);
            assertThat(upgrade.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT installation_id FROM artio_license_state", java.util.UUID.class)).isEqualTo(identity);
        }
    }
}
