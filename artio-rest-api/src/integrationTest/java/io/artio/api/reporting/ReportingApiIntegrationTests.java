package io.artio.api.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = io.artio.api.ArtioRestApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ReportingApiIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final KeyPair ISSUER;
    private static final Path TRUST;
    static {
        try {
            ISSUER = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            TRUST = Files.createTempFile("artio-report-public-trust-", ".json");
            TRUST.toFile().deleteOnExit();
            Files.writeString(TRUST, new ObjectMapper().writeValueAsString(Map.of("fixture", Base64.getEncoder().encodeToString(ISSUER.getPublic().getEncoded()))));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired JwtEncoder jwt;
    @Autowired ObjectMapper mapper;
    @Autowired io.artio.api.licensing.LicensingService licensing;
    private UUID owner;
    private UUID buyer;
    private String ownerToken;
    private String customerToken;
    private int grantRevision;
    private final Instant issueBase = Instant.now().minusSeconds(3 * 86400);

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "report-integration-jwt-key-material-at-least-32-bytes");
        registry.add("artio.security.encryption-key", () -> "report-integration-aes-key-material-at-least-32-bytes");
        registry.add("artio.setup.token", () -> "report-integration-setup-token-at-least-32-bytes");
        registry.add("artio.licensing.public-keys-file", TRUST::toString);
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.notifications.worker.enabled", () -> "false");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.extensions.docker.enabled", () -> "false");
        registry.add("artio.rate-limit.requests-per-minute", () -> "10000");
    }

    @BeforeEach
    void privateFixtureDatabase() {
        // This fresh Testcontainers database is owned solely by this class, never a Compose installation.
        jdbc.execute("TRUNCATE users CASCADE");
        jdbc.update("UPDATE artio_license_state SET signed_document=NULL,revision=revision+1");
        licensing.reload();
        owner = user("SUPERADMIN"); buyer = user("CUSTOMER");
        ownerToken = token(owner, "SUPERADMIN"); customerToken = token(buyer, "CUSTOMER");
    }

    @Test
    void communityReportsSeparateCurrenciesCapturedTenderAndRefundsWithoutLeakingAdvancedFields() throws Exception {
        seedCohort();
        var response = get("/api/v1/admin/reports", ownerToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var report = response.getBody();
        assertThat(report.path("orders").asLong()).isEqualTo(7);
        assertThat(report.path("activeCustomers").asLong()).isEqualTo(3);
        assertThat(report.path("salesByCurrency").size()).isEqualTo(2);
        JsonNode chf = currency(report.path("salesByCurrency"), "CHF");
        assertThat(chf.path("orders").asLong()).isEqualTo(6);
        money(chf, "capturedAmount", "180"); money(chf, "refundedAmount", "40"); money(chf, "netCapturedAmount", "140");
        money(currency(report.path("salesByCurrency"), "EUR"), "netCapturedAmount", "200");
        assertThat(report.has("revenue")).isFalse();
        assertThat(report.has("topVariants")).isFalse();
        assertThat(report.has("averageCustomerLifetimeValue")).isFalse();
        assertThat(java.util.stream.StreamSupport.stream(report.path("ordersByStatus").spliterator(), false)
                .map(status -> status.path("status").asText()).toList())
                .containsExactly("CANCELLED", "PAID", "PENDING");
        assertThat(get("/api/v1/admin/orders/recent", ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void advancedReportsRequireTheirOwnGrantAndKeepVariantAndLifetimeMoneySeparated() throws Exception {
        UUID variant = seedCohort();
        assertDenied(get("/api/v1/admin/reports/advanced", ownerToken));
        install(List.of("LOYALTY"), false);
        assertDenied(get("/api/v1/admin/reports/advanced", ownerToken));
        install(List.of("ADVANCED_REPORTING"), false);
        var result = get("/api/v1/admin/reports/advanced", ownerToken);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        var report = result.getBody();
        assertThat(report.path("topVariants").size()).isEqualTo(3);
        var chfVariant = currency(report.path("topVariants"), "CHF");
        assertThat(chfVariant.path("variantId").asText()).isEqualTo(variant.toString());
        assertThat(chfVariant.path("variantName").asText()).isEqualTo("Shared variant");
        assertThat(chfVariant.path("productName").asText()).isEqualTo("Reporting fixture");
        money(chfVariant, "unitsOnCapturedOrders", "3"); money(chfVariant, "recordedLineValue", "150");
        money(currency(report.path("topVariants"), "EUR"), "unitsOnCapturedOrders", "4");
        var lifetime = currency(report.path("customerLifetimeValues"), "CHF");
        assertThat(lifetime.path("customers").asLong()).isEqualTo(3);
        money(lifetime, "capturedAmount", "480"); money(lifetime, "refundedAmount", "140");
        money(lifetime, "netCapturedAmount", "340"); money(lifetime, "averageNetCapturedValue", "113.33");
        money(currency(report.path("customerLifetimeValues"), "EUR"), "averageNetCapturedValue", "200");
        install(List.of("ADVANCED_REPORTING"), true);
        assertDenied(get("/api/v1/admin/reports/advanced", ownerToken));
        assertThat(get("/api/v1/admin/reports", ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rankLimitIsTenPerCurrencyAndReportPeriodIsBounded() throws Exception {
        for (String currency : List.of("CHF", "EUR")) for (int quantity = 1; quantity <= 11; quantity++) {
            UUID order = order(buyer, currency, Integer.toString(quantity), "PAID", Instant.now().minusSeconds(3600));
            payment(order, buyer, currency, Integer.toString(quantity), "0", "CAPTURED");
            item(order, UUID.randomUUID(), currency, quantity, "1");
        }
        install(List.of("ADVANCED_REPORTING"), false);
        var result = get("/api/v1/admin/reports/advanced?days=1", ownerToken);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().path("topVariants").size()).isEqualTo(20);
        for (JsonNode row : result.getBody().path("topVariants")) {
            assertThat(row.path("rank").asInt()).isBetween(1, 10);
            assertThat(row.path("unitsOnCapturedOrders").decimalValue()).isGreaterThan(BigDecimal.ONE);
        }
        for (String invalid : List.of("0", "-1", "366", "text"))
            assertThat(get("/api/v1/admin/reports?days=" + invalid, ownerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reportingRolesAndCommunityCurrencyAndFraudOperationsWorkAgainstPostgres() throws Exception {
        assertThat(http.getForEntity("/api/v1/admin/reports", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/admin/reports", customerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/admin/reports/advanced", customerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var rate = exchange("/api/v1/admin/currency/rates", HttpMethod.POST, Map.of("baseCurrency", "CHF", "quoteCurrency", "JPY", "rate", "2.5"), ownerToken);
        assertThat(rate.getStatusCode()).isEqualTo(HttpStatus.OK);
        money(get("/api/v1/currency/convert?amount=1&from=CHF&to=JPY", customerToken).getBody(), "amount", "2");
        assertThat(exchange("/api/v1/admin/currency/rates", HttpMethod.POST, Map.of("baseCurrency", "CHF", "quoteCurrency", "KWD", "rate", "1.2345", "validFrom", Instant.now().minusSeconds(60).toString()), ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        money(get("/api/v1/currency/convert?amount=1&from=CHF&to=KWD", customerToken).getBody(), "amount", "1.234");
        money(get("/api/v1/currency/convert?amount=1.2355&from=KWD&to=KWD", customerToken).getBody(), "amount", "1.236");
        assertThat(get("/api/v1/currency/convert?amount=1&from=CHF&to=ZZZ", customerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE users SET created_at=now()-interval '60 seconds' WHERE id=?", buyer);
        UUID assessed = order(buyer, "CHF", "3000", "PENDING", Instant.now());
        order(buyer, "CHF", "10", "PENDING", Instant.now()); order(buyer, "CHF", "10", "PENDING", Instant.now());
        var fraud = exchange("/api/v1/admin/orders/" + assessed + "/fraud-assessment", HttpMethod.POST, null, ownerToken);
        assertThat(fraud.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fraud.getBody().path("signals").toString()).contains("high_order_value", "order_velocity", "new_account");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fraud_reviews WHERE order_id=?", Integer.class, assessed)).isEqualTo(1);
    }

    private UUID seedCohort() {
        UUID variant = UUID.randomUUID(); UUID other = user("CUSTOMER");
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog_product(id,name,sku,active,created_at,updated_at) VALUES (?,'Reporting fixture',?,true,now(),now())", product, "REPORT-P-" + product.toString().substring(0,8).toUpperCase());
        jdbc.update("INSERT INTO catalog_variant(id,product_id,name,sku,active,created_at,updated_at) VALUES (?,?,'Shared variant',?,true,now(),now())", variant, product, "REPORT-V-" + variant.toString().substring(0,8).toUpperCase());
        Instant recent = Instant.now().minusSeconds(86400);
        UUID first = order(owner, "CHF", "100", "PAID", recent);
        payment(first, owner, "CHF", "40", "5", "PARTIALLY_REFUNDED"); payment(first, owner, "CHF", "60", "15", "PARTIALLY_REFUNDED"); item(first, variant, "CHF", 2, "50");
        UUID second = order(buyer, "CHF", "50", "PAID", recent); payment(second, buyer, "CHF", "50", "0", "CAPTURED"); item(second, variant, "CHF", 1, "50");
        UUID euro = order(owner, "EUR", "200", "PAID", recent); payment(euro, owner, "EUR", "200", "0", "CAPTURED"); item(euro, variant, "EUR", 4, "50");
        UUID pending = order(owner, "CHF", "999", "PENDING", recent); payment(pending, owner, "CHF", "999", "0", "PENDING"); item(pending, UUID.randomUUID(), "CHF", 1000, "0.999");
        UUID partial = order(buyer, "CHF", "80", "PENDING", recent); payment(partial, buyer, "CHF", "10", "0", "CAPTURED"); item(partial, UUID.randomUUID(), "CHF", 8, "10");
        UUID cancelled = order(owner, "CHF", "400", "CANCELLED", recent); payment(cancelled, owner, "CHF", "400", "0", "FAILED");
        UUID returned = order(other, "CHF", "20", "PAID", recent); payment(returned, other, "CHF", "20", "20", "REFUNDED"); item(returned, UUID.randomUUID(), "CHF", 1, "20");
        UUID old = order(owner, "CHF", "300", "PAID", Instant.now().minusSeconds(40 * 86400)); payment(old, owner, "CHF", "300", "100", "PARTIALLY_REFUNDED"); item(old, UUID.randomUUID(), "CHF", 30, "10");
        UUID future = order(owner, "USD", "1000", "PAID", Instant.now().plusSeconds(86400)); payment(future, owner, "USD", "1000", "0", "CAPTURED");
        return variant;
    }

    private UUID order(UUID user, String currency, String amount, String status, Instant created) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) VALUES (?,?,?,? ,0,0,?,?,'Fixture','Fixture',?,'CH','CH','CH','{}',?)",
                id, user, status, new BigDecimal(amount), new BigDecimal(amount), currency, Timestamp.from(created), id.toString());
        return id;
    }
    private void payment(UUID order, UUID user, String currency, String amount, String refunded, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,refunded_amount,currency,method,status,provider_reference,created_at,updated_at) VALUES (?,?,?,?,?,?,'EXTENSION',?,?,now(),now())",
                id, order, user, new BigDecimal(amount), new BigDecimal(refunded), currency, status, "report-fixture:" + id);
        if (new BigDecimal(refunded).signum() > 0) jdbc.update("INSERT INTO payment_refunds(id,payment_id,idempotency_key,amount,currency,status) VALUES (?,?,?,?,?,'COMPLETED')", UUID.randomUUID(), id, id.toString(), new BigDecimal(refunded), currency);
    }
    private void item(UUID order, UUID variant, String currency, int quantity, String price) {
        jdbc.update("INSERT INTO order_items(id,order_id,variant_id,quantity,unit_price,currency) VALUES (?,?,?,?,?,?)", UUID.randomUUID(), order, variant, quantity, new BigDecimal(price), currency);
    }
    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,'fixture-hash','Report','Fixture',?,now())", id, id + "@example.test", role);
        return id;
    }
    private String token(UUID user, String role) {
        Instant now = Instant.now();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder().subject(user.toString()).claim("role", role).issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
    }
    private void install(List<String> features, boolean expired) throws Exception {
        Instant now = Instant.now(); Instant issued = issueBase.plusSeconds(++grantRevision);
        byte[] payload = mapper.writeValueAsBytes(Map.of("version", 1, "licenseId", UUID.randomUUID().toString(), "installationId", licensing.status().installationId().toString(), "issuedAt", issued.getEpochSecond(), "notBefore", issued.getEpochSecond(), "expiresAt", (expired ? now.minusSeconds(3600) : now.plusSeconds(86400)).getEpochSecond(), "graceUntil", (expired ? now.minusSeconds(1) : now.plusSeconds(172800)).getEpochSecond(), "features", features));
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(ISSUER.getPrivate()); signature.update(payload);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var envelope = Map.of("keyId", "fixture", "payload", encoder.encodeToString(payload), "signature", encoder.encodeToString(signature.sign()));
        assertThat(exchange("/api/v1/admin/system/license", HttpMethod.POST, envelope, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    private ResponseEntity<JsonNode> get(String path, String token) { return exchange(path, HttpMethod.GET, null, token); }
    private ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private static JsonNode currency(JsonNode rows, String currency) {
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false).filter(row -> currency.equals(row.path("currency").asText())).findFirst().orElseThrow();
    }
    private static void money(JsonNode row, String field, String expected) { assertThat(row.path(field).decimalValue()).isEqualByComparingTo(expected); }
    private static void assertDenied(ResponseEntity<JsonNode> result) {
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN); assertThat(result.getBody().path("code").asText()).isEqualTo("FEATURE_NOT_LICENSED");
    }
}
