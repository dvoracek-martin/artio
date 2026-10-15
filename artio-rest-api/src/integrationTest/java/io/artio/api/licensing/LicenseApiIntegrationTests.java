package io.artio.api.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
class LicenseApiIntegrationTests {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final KeyPair ISSUER;
    private static final Path PUBLIC_KEYS;
    static {
        try {
            ISSUER = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            PUBLIC_KEYS = Files.createTempFile("artio-license-api-public-keys-", ".json");
            PUBLIC_KEYS.toFile().deleteOnExit();
            Files.writeString(PUBLIC_KEYS, new ObjectMapper().writeValueAsString(Map.of("fixture",
                    Base64.getEncoder().encodeToString(ISSUER.getPublic().getEncoded()))));
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwt;
    @Autowired ObjectMapper mapper;
    @Autowired LicensingService licensing;
    // Availability only: financial assertions use real PostgreSQL gift-card tender, never mocked provider outcomes.
    @org.springframework.test.context.bean.override.mockito.MockitoBean io.artio.extensions.commerce.PaymentProvider paymentProvider;
    @LocalServerPort int port;
    private UUID owner;
    private String ownerToken;
    private String adminToken;
    private String customerToken;
    private UUID customer;

    @org.junit.jupiter.api.AfterEach
    void noExternalProviderOutcomeWasSimulated() {
        org.mockito.Mockito.verifyNoInteractions(paymentProvider);
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "license-api-integration-jwt-key-at-least-32-bytes");
        registry.add("artio.security.encryption-key", () -> "license-api-integration-aes-key-at-least-32-bytes");
        registry.add("artio.setup.token", () -> "license-api-integration-setup-token-at-least-32-bytes");
        registry.add("artio.licensing.public-keys-file", PUBLIC_KEYS::toString);
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.notifications.worker.enabled", () -> "false");
        registry.add("artio.events.kafka.enabled", () -> "false");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.extensions.docker.enabled", () -> "false");
        registry.add("artio.checkout.shipping-cost", () -> "0.00");
        registry.add("artio.rate-limit.requests-per-minute", () -> "10000");
    }

    @BeforeEach
    void authenticatedRolesAndCommunityState() {
        jdbc.update("UPDATE artio_license_state SET signed_document=NULL,revision=revision+1");
        licensing.reload();
        owner = user("SUPERADMIN");
        ownerToken = token(owner, "SUPERADMIN");
        adminToken = token(user("ADMIN"), "ADMIN");
        customer = user("CUSTOMER");
        customerToken = token(customer, "CUSTOMER");
    }

    @Test
    void onlyOwnerCanReadInstallOrRefreshAndResponsesDoNotExposeSignedContents() throws Exception {
        String path = "/api/v1/admin/system/license";
        assertThat(http.getForEntity(path, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String denied : List.of(adminToken, customerToken)) {
            assertThat(exchange(path, HttpMethod.GET, null, denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(path, HttpMethod.POST, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange(path + "/refresh", HttpMethod.POST, null, denied).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var initial = exchange(path, HttpMethod.GET, null, ownerToken);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody().path("edition").asText()).isEqualTo("COMMUNITY");
        UUID identity = UUID.fromString(initial.getBody().path("installationId").asText());
        byte[] signed = signed(identity);
        var installed = exchange(path, HttpMethod.POST, signed, ownerToken);
        assertThat(installed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(installed.getBody().path("edition").asText()).isEqualTo("ENTERPRISE");
        assertThat(installed.getBody().path("features").toString()).contains("LOYALTY");
        assertThat(installed.getBody().toString()).doesNotContain("payload", "signature", "licenseId", "keyId");
        assertThat(exchange(path, HttpMethod.POST, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), ownerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(path, HttpMethod.POST, new String(signed, java.nio.charset.StandardCharsets.UTF_8)
                .getBytes(java.nio.charset.StandardCharsets.UTF_16LE), ownerToken).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(licensing.allows(EnterpriseFeature.LOYALTY)).isTrue();
        var refresh = exchange(path + "/refresh", HttpMethod.POST, null, ownerToken);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refresh.getBody().path("refreshStatus").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='LICENSE_CHECK'", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id=? AND action='LICENSE_INSTALL'", Integer.class, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isEqualTo(new String(signed, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void administratorsCanReadOnlySafeFeatureAvailabilityWhileCustomerCannot() throws Exception {
        String path = "/api/v1/admin/license/availability";
        assertThat(http.getForEntity(path, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(path, HttpMethod.GET, null, customerToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var community = exchange(path, HttpMethod.GET, null, adminToken);
        assertThat(community.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(community.getBody().path("edition").asText()).isEqualTo("COMMUNITY");
        assertThat(community.getBody().path("features")).isEmpty();
        assertThat(community.getBody().toString()).doesNotContain("installationId", "licenseId", "payload", "signature", "refresh", "keyId");

        installFeatures(List.of("ADVANCED_REPORTING"), false);
        var enterprise = exchange(path, HttpMethod.GET, null, adminToken);
        assertThat(enterprise.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enterprise.getBody().path("edition").asText()).isEqualTo("ENTERPRISE");
        assertThat(enterprise.getBody().path("features").toString()).contains("ADVANCED_REPORTING");
        assertThat(enterprise.getBody().toString()).doesNotContain("installationId", "licenseId", "payload", "signature", "refresh", "keyId");
    }

    @Test
    void boundedOwnerUploadRejectsBothFixedAndChunkedBodiesWithoutChangingCommunity() throws Exception {
        byte[] oversized = new byte[65_537];
        assertThat(exchange("/api/v1/admin/system/license", HttpMethod.POST, oversized, ownerToken).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/system/license"))
                    .timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer " + ownerToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(oversized))).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(413);
        }
        assertThat(licensing.status().edition()).isEqualTo("COMMUNITY");
        assertThat(jdbc.queryForObject("SELECT signed_document FROM artio_license_state", String.class)).isNull();
    }

    private org.springframework.http.ResponseEntity<JsonNode> exchange(String path, HttpMethod method, byte[] body, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    void communityCheckoutPaymentAndRefundWorkWithoutCreatingNewPointsEntitlement() throws Exception {
        Checkout checkout = checkout(true);
        assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, checkout.id())).isFalse();
        UUID payment = payWithGiftCard(checkout.id());
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, checkout.id())).isEqualTo("PAID");
        assertThat(json("/api/v1/loyalty/me", HttpMethod.GET, null, customerToken, null).getBody().path("points").asLong()).isZero();
        assertNotLicensed(json("/api/v1/loyalty/earn/" + checkout.id(), HttpMethod.POST, null, customerToken, null));
        var refunded = json("/api/v1/payments/" + payment + "/refund", HttpMethod.POST, Map.of("amount", 10), ownerToken, UUID.randomUUID().toString());
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refunded.getBody().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_transactions WHERE order_id=?", Integer.class, checkout.id())).isZero();
    }

    @Test
    void orderTimePromiseSurvivesExpiryPaymentRedemptionRefundAndCheckoutReplay() throws Exception {
        installFeatures(List.of("LOYALTY"), false);
        Checkout checkout = checkout();
        assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, checkout.id())).isTrue();
        installFeatures(List.of("LOYALTY"), true);
        assertThat(licensing.status().state()).isEqualTo("EXPIRED");
        UUID payment = payWithGiftCard(checkout.id());
        assertThat(json("/api/v1/loyalty/me", HttpMethod.GET, null, customerToken, null).getBody().path("points").asLong()).isEqualTo(216);
        var earnedAgain = json("/api/v1/loyalty/earn/" + checkout.id(), HttpMethod.POST, null, customerToken, null);
        assertThat(earnedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(earnedAgain.getBody().path("points").asLong()).isEqualTo(216);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_transactions WHERE order_id=? AND kind='EARN'", Integer.class, checkout.id())).isEqualTo(1);
        String redemptionKey = UUID.randomUUID().toString();
        var redeemed = json("/api/v1/loyalty/redeem", HttpMethod.POST, Map.of("points", 100), customerToken, redemptionKey);
        assertThat(redeemed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redeemed.getBody().path("points").asLong()).isEqualTo(116);
        assertThat(json("/api/v1/loyalty/redeem", HttpMethod.POST, Map.of("points", 100), customerToken, redemptionKey).getBody().path("points").asLong()).isEqualTo(116);
        String refundKey = UUID.randomUUID().toString();
        var refunded = json("/api/v1/payments/" + payment + "/refund", HttpMethod.POST, Map.of("amount", 10), ownerToken, refundKey);
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refunded.getBody().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(json("/api/v1/loyalty/me", HttpMethod.GET, null, customerToken, null).getBody().path("points").asLong()).isEqualTo(107);
        assertThat(json("/api/v1/payments/" + payment + "/refund", HttpMethod.POST, Map.of("amount", 10), ownerToken, refundKey).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_transactions WHERE order_id=? AND kind='REFUND'", Integer.class, checkout.id())).isEqualTo(1);
        var replay = json("/api/v1/cart/checkout", HttpMethod.POST, checkoutRequest(), customerToken, checkout.key());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("orderId").asText()).isEqualTo(checkout.id().toString());
        assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, checkout.id())).isTrue();
        Checkout newCommunityOrder = checkout();
        assertThat(jdbc.queryForObject("SELECT loyalty_eligible FROM orders WHERE id=?", Boolean.class, newCommunityOrder.id())).isFalse();
        assertThat(json("/api/v1/admin/loyalty/" + customer, HttpMethod.GET, null, ownerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json("/api/v1/privacy/export", HttpMethod.GET, null, customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void newProgramAdjustmentsRequireLicenseButCommittedAdjustmentReplayDoesNot() throws Exception {
        String path = "/api/v1/admin/loyalty/" + customer + "/adjust";
        Map<String, Object> request = Map.of("points", 250, "reason", "Approved service award");
        assertNotLicensed(json(path, HttpMethod.POST, request, ownerToken, UUID.randomUUID().toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_accounts WHERE user_id=?", Integer.class, customer)).isZero();
        installFeatures(List.of("LOYALTY"), false);
        String key = UUID.randomUUID().toString();
        assertThat(json(path, HttpMethod.POST, request, ownerToken, key).getStatusCode()).isEqualTo(HttpStatus.OK);
        installFeatures(List.of("LOYALTY"), true);
        assertThat(json(path, HttpMethod.POST, request, ownerToken, key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotLicensed(json(path, HttpMethod.POST, request, ownerToken, UUID.randomUUID().toString()));
        assertThat(jdbc.queryForObject("SELECT points FROM loyalty_accounts WHERE user_id=?", Long.class, customer)).isEqualTo(250);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_transactions WHERE user_id=? AND kind='ADJUST'", Integer.class, customer)).isEqualTo(1);
    }

    @Test
    void subscriptionCreationAndReactivationRequireLicenseWhileExistingContractCanBeReadPausedAndCancelled() throws Exception {
        var request = Map.of("userId", customer.toString(), "frequency", "MONTHLY", "items", List.of(Map.of("variantId", UUID.randomUUID().toString(), "quantity", 1)));
        assertNotLicensed(json("/api/v1/admin/subscriptions", HttpMethod.POST, request, ownerToken, null));
        installFeatures(List.of("SUBSCRIPTIONS"), false);
        var created = json("/api/v1/admin/subscriptions", HttpMethod.POST, request, ownerToken, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = created.getBody().path("id").asText();
        assertNotLicensed(json("/api/v1/admin/loyalty/" + customer + "/adjust", HttpMethod.POST,
                Map.of("points", 250, "reason", "Different Enterprise feature"), ownerToken, UUID.randomUUID().toString()));
        installFeatures(List.of("SUBSCRIPTIONS"), true);
        assertThat(json("/api/v1/subscriptions", HttpMethod.GET, null, customerToken, null).getBody().size()).isEqualTo(1);
        String status = "/api/v1/subscriptions/" + id + "/status";
        assertThat(json(status, HttpMethod.PUT, Map.of("status", "ACTIVE"), customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(status, HttpMethod.PUT, Map.of("status", "PAUSED"), customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotLicensed(json(status, HttpMethod.PUT, Map.of("status", "ACTIVE"), customerToken, null));
        assertThat(jdbc.queryForObject("SELECT status FROM subscriptions WHERE id=?", String.class, UUID.fromString(id))).isEqualTo("PAUSED");
        assertThat(json(status, HttpMethod.PUT, Map.of("status", "CANCELLED"), customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(status, HttpMethod.PUT, Map.of("status", "CANCELLED"), customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(status, HttpMethod.PUT, Map.of("status", "PAUSED"), ownerToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertNotLicensed(json("/api/v1/admin/subscriptions", HttpMethod.POST, request, ownerToken, null));
    }

    private void installFeatures(List<String> features, boolean expired) throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        Instant issued = now.minus(java.time.Duration.ofDays(expired ? 2 : 3));
        byte[] document = signed(licensing.status().installationId(), features, issued,
                expired ? now.minusSeconds(3600) : now.plusSeconds(86400), expired ? now.minusSeconds(1) : now.plusSeconds(172800));
        assertThat(exchange("/api/v1/admin/system/license", HttpMethod.POST, document, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Checkout checkout() throws Exception {
        return checkout(false);
    }

    private Checkout checkout(boolean attemptClientSuppliedEligibility) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var product = json("/api/v1/products", HttpMethod.POST, Map.of("name", "Entitlement fixture", "description", "Fixture", "sku", "LIC-" + suffix), ownerToken, null);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var variant = json("/api/v1/products/" + product.getBody().path("id").asText() + "/variants", HttpMethod.POST,
                Map.of("name", "Default", "sku", "LIC-V-" + suffix), ownerToken, null);
        assertThat(variant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID variantId = UUID.fromString(variant.getBody().path("id").asText());
        assertThat(json("/api/v1/prices", HttpMethod.POST, new io.artio.api.pricing.PricingApplicationService.PriceRequest(
                variantId, "CH", "WEB", new java.math.BigDecimal("200.00"), "CHF", Instant.now().minusSeconds(60), null), ownerToken, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var warehouse = json("/api/v1/warehouses", HttpMethod.POST, new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("LIC-" + suffix, "Entitlement fixture", "CH"), ownerToken, null);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json("/api/v1/warehouses/" + warehouse.getBody().path("id").asText() + "/stock/" + variantId + "/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(java.math.BigDecimal.TEN), ownerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json("/api/v1/cart/items", HttpMethod.POST, new io.artio.api.orders.CartController.AddItemRequest(
                variantId, java.math.BigDecimal.ONE, "CH", "WEB", "CH"), customerToken, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = UUID.randomUUID().toString();
        var body = new java.util.HashMap<String, Object>(checkoutRequest());
        if (attemptClientSuppliedEligibility) body.put("loyaltyEligible", true);
        var placed = json("/api/v1/cart/checkout", HttpMethod.POST, body, customerToken, key);
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Checkout(UUID.fromString(placed.getBody().path("orderId").asText()), key);
    }

    private UUID payWithGiftCard(UUID order) throws Exception {
        var card = json("/api/v1/admin/gift-cards", HttpMethod.POST, Map.of("amount", 1000, "currency", "CHF"), ownerToken, null);
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        var paid = json("/api/v1/gift-cards/redeem", HttpMethod.POST, Map.of("orderId", order.toString(), "code", card.getBody().path("code").asText()), customerToken, null);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().path("orderStatus").asText()).isEqualTo("PAID");
        return jdbc.queryForObject("SELECT id FROM payments WHERE order_id=? AND method='GIFT_CARD'", UUID.class, order);
    }

    private Map<String, Object> checkoutRequest() {
        return Map.of("shippingAddress", "Fixture Street 1", "billingAddress", "Fixture Street 1", "shippingCountry", "CH", "locale", "en");
    }

    private org.springframework.http.ResponseEntity<JsonNode> json(String path, HttpMethod method, Object body, String token, String key) throws Exception {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        return http.exchange(path, method, new HttpEntity<>(body == null ? null : mapper.writeValueAsBytes(body), headers), JsonNode.class);
    }

    private static void assertNotLicensed(org.springframework.http.ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().path("code").asText()).isEqualTo("FEATURE_NOT_LICENSED");
    }

    private record Checkout(UUID id, String key) { }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,'fixture-hash','License','Fixture',?,now())",
                id, "license-" + id + "@example.test", role);
        return id;
    }

    private String token(UUID user, String role) {
        Instant now = Instant.now();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .subject(user.toString()).claim("role", role).issuedAt(now).expiresAt(now.plusSeconds(300)).build())).getTokenValue();
    }

    private byte[] signed(UUID identity) throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        return signed(identity, List.of("LOYALTY"), now, now.plusSeconds(3600), now.plusSeconds(7200));
    }

    private byte[] signed(UUID identity, List<String> features, Instant issued, Instant expires, Instant grace) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(Map.of("version", 1, "licenseId", UUID.randomUUID().toString(),
                "installationId", identity.toString(), "issuedAt", issued.getEpochSecond(), "notBefore", issued.getEpochSecond(),
                "expiresAt", expires.getEpochSecond(), "graceUntil", grace.getEpochSecond(), "features", features));
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(ISSUER.getPrivate()); signature.update(payload);
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return mapper.writeValueAsBytes(Map.of("keyId", "fixture", "payload", encoder.encodeToString(payload), "signature", encoder.encodeToString(signature.sign())));
    }
}
