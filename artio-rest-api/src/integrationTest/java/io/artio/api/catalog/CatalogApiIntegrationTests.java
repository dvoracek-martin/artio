package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.artio.api.error.ApiError;
import io.artio.extensions.commerce.PaymentProvider;
import io.artio.api.catalog.media.ProductMediaController;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "artio.payments.extension-plugin-id=integration-test")
@AutoConfigureTestRestTemplate
@Import(CatalogApiIntegrationTests.LocalCacheConfiguration.class)
class CatalogApiIntegrationTests {
    private static final String ACTIVE_TEST_ENCRYPTION_KEY = "integration-test-encryption-key-value-32-bytes-minimum";
    private static final String PREVIOUS_TEST_ENCRYPTION_KEY = "previous-test-encryption-key-value-32-bytes-minimum";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicReference<String> PROVIDER_RESPONSE = new AtomicReference<>("{\"status\":\"open\",\"payment_status\":\"unpaid\",\"amount\":\"12.50\",\"currency\":\"CHF\"}");
    private static final AtomicReference<RuntimeException> PROVIDER_CHECKOUT_FAILURE = new AtomicReference<>();
    private static final AtomicReference<Map<String, String>> PROVIDER_WEBHOOK_HEADERS = new AtomicReference<>(Map.of());

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private io.artio.api.extensions.ExtensionRegistryCredentialService extensionRegistryCredentials;

    @Autowired
    private io.artio.api.extensions.OciArchiveInspector ociArchiveInspector;

    @Autowired
    private io.artio.api.extensions.OciArchiveQuarantineStore archiveQuarantineStore;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private io.artio.api.inventory.InventoryApplicationService inventory;

    private io.artio.api.inventory.ReservationExpiryScheduler reservationExpiryScheduler;

    @Autowired
    private VariantApplicationService variantApplicationService;

    @Autowired
    private ProductApplicationService productApplicationService;

    @Autowired
    private io.artio.api.notifications.OrderEventOutboxPublisher orderEventOutboxPublisher;

    @Autowired
    private io.artio.api.security.EncryptionService encryption;

    @Autowired
    private io.artio.api.extensions.CommerceCapabilityInvoker commerceCapabilityInvoker;

    @Test
    void postgresDetectsEqualPriorityOverlappingExclusiveCapabilityScopes() {
        Set<String> databaseRules = jdbc.query("SELECT capability_id || '/' || operation_name FROM extension_exclusive_routing_rules",
                (rs, row) -> rs.getString(1)).stream().collect(java.util.stream.Collectors.toSet());
        Set<String> coreRules = io.artio.extensions.CapabilityRegistry.capabilityIds().stream()
                .flatMap(capabilityId -> io.artio.extensions.CapabilityRegistry.find(capabilityId).orElseThrow().operations().entrySet().stream()
                        .filter(operation -> operation.getValue().policy() == io.artio.extensions.CapabilityRegistry.Policy.EXCLUSIVE)
                        .map(operation -> capabilityId + "/" + operation.getKey()))
                .collect(java.util.stream.Collectors.toSet());
        Boolean overlap = jdbc.queryForObject("SELECT artio_exclusive_scopes_overlap(?::jsonb, ?, ?::jsonb, ?)",
                Boolean.class, "{\"country\":\"CH\"}", 5, "{\"currency\":\"CHF\"}", 5);
        Boolean differentPriority = jdbc.queryForObject("SELECT artio_exclusive_scopes_overlap(?::jsonb, ?, ?::jsonb, ?)",
                Boolean.class, "{\"country\":\"CH\"}", 5, "{\"country\":\"CH\"}", 6);
        Boolean disjoint = jdbc.queryForObject("SELECT artio_exclusive_scopes_overlap(?::jsonb, ?, ?::jsonb, ?)",
                Boolean.class, "{\"country\":\"CH\"}", 5, "{\"country\":\"DE\"}", 5);

        assertThat(databaseRules).isEqualTo(coreRules);
        assertThat(overlap).isTrue();
        assertThat(differentPriority).isFalse();
        assertThat(disjoint).isFalse();

        UUID firstInstance = createRouteGuardInstance();
        UUID secondInstance = createRouteGuardInstance();
        String firstPlugin = jdbc.queryForObject("SELECT plugin_id FROM extension_plugin_instances WHERE instance_id=?", String.class, firstInstance);
        String secondPlugin = jdbc.queryForObject("SELECT plugin_id FROM extension_plugin_instances WHERE instance_id=?", String.class, secondInstance);
        try {
            jdbc.update("INSERT INTO extension_capability_bindings(binding_id,instance_id,capability_id,operation_name,scope,priority) "
                            + "VALUES (?,?,'catalog.enrichment.v1','enrich','{\"country\":\"CH\"}'::jsonb,5)",
                    UUID.randomUUID(), firstInstance);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO extension_capability_bindings(binding_id,instance_id,capability_id,operation_name,scope,priority) "
                            + "VALUES (?,?,'catalog.enrichment.v1','enrich','{\"currency\":\"CHF\"}'::jsonb,5)",
                    UUID.randomUUID(), secondInstance)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.update("DELETE FROM extension_capability_bindings WHERE instance_id IN (?,?)", firstInstance, secondInstance);
            jdbc.update("DELETE FROM extension_plugin_instances WHERE instance_id IN (?,?)", firstInstance, secondInstance);
            jdbc.update("DELETE FROM extension_plugin_releases WHERE plugin_id IN (?,?)", firstPlugin, secondPlugin);
            jdbc.update("DELETE FROM extension_plugins WHERE plugin_id IN (?,?)", firstPlugin, secondPlugin);
        }
    }

    private UUID createRouteGuardInstance() {
        String pluginId = "route-" + UUID.randomUUID().toString().replace("-", "");
        String image = "ghcr.io/example/route@sha256:" + "a".repeat(64);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state) VALUES (?,?::jsonb,?,'CONFIGURED')",
                pluginId, "{\"id\":\"" + pluginId + "\",\"version\":\"1.0.0\"}", image);
        Long releaseId = jdbc.queryForObject("INSERT INTO extension_plugin_releases(plugin_id,version,image_reference,manifest) "
                + "VALUES (?,'1.0.0',?,?::jsonb) RETURNING release_id", Long.class, pluginId, image,
                "{\"id\":\"" + pluginId + "\",\"version\":\"1.0.0\"}");
        UUID instanceId = UUID.randomUUID();
        jdbc.update("INSERT INTO extension_plugin_instances(instance_id,plugin_id,instance_key,release_id,compatibility_mode,"
                        + "lifecycle_state,runtime_identity) VALUES (?,?,'default',?,'CAPABILITY_V2','ENABLED',?)",
                instanceId, pluginId, releaseId, "route-" + instanceId.toString().replace("-", ""));
        return instanceId;
    }

    @Autowired
    private io.artio.api.payments.CheckoutFailureCompensation checkoutFailureCompensation;

    private io.artio.api.payments.PaymentReconciliationWorker paymentReconciliationWorker;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private io.artio.api.orders.OrderUpdatePublisher orderUpdates;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<PaymentProvider> paymentProviders;

    private String adminToken;
    private UUID adminUserId;
    private String adminTotpSecret;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> ACTIVE_TEST_ENCRYPTION_KEY);
        registry.add("artio.security.encryption-keyring", () -> "{\"" + io.artio.api.security.EncryptionService.keyId(PREVIOUS_TEST_ENCRYPTION_KEY)
                + "\":\"" + PREVIOUS_TEST_ENCRYPTION_KEY + "\"}");
        registry.add("artio.setup.token", () -> "integration-test-setup-token-value-32-bytes-minimum");
        registry.add("artio.extensions.trusted-registries", () -> {
            String acceptanceRegistry = System.getenv("ARTIO_EXTENSION_TRUSTED_REGISTRIES");
            return acceptanceRegistry == null || acceptanceRegistry.isBlank()
                    ? "ghcr.io" : "ghcr.io," + acceptanceRegistry;
        });
        registry.add("artio.inventory.expiry-interval-ms", () -> "3600000");
        registry.add("artio.events.outbox-poll-interval-ms", () -> "3600000");
        registry.add("artio.events.kafka.enabled", () -> "true");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.workers.enabled", () -> "false");
        // This aggregate business suite legitimately exceeds one client's default minute quota.
        // Dedicated RateLimitFilterTests retain real threshold/fallback/error-contract coverage.
        registry.add("artio.rate-limit.requests-per-minute", () -> "10000");
        registry.add("artio.payments.provider", () -> "none");
    }

    @BeforeEach
    void authenticateAdminForCatalogAdministration() {
        // Exercise maintenance explicitly without enabling background schedules in API integration tests.
        reservationExpiryScheduler = new io.artio.api.inventory.ReservationExpiryScheduler(
                inventory, jdbc, orderUpdates, transactionManager);
        paymentReconciliationWorker = new io.artio.api.payments.PaymentReconciliationWorker(
                jdbc, inventory, orderUpdates, transactionManager, paymentProviders);
        PROVIDER_CHECKOUT_FAILURE.set(null);
        PROVIDER_WEBHOOK_HEADERS.set(Map.of());
        UUID userId = UUID.randomUUID();
        adminUserId = userId;
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) "
                        + "VALUES (?,?,?,?,?,'ADMIN',now())",
                userId, "admin-" + userId + "@example.test", new BCryptPasswordEncoder().encode("admin-password-123"),
                "Test", "Administrator");
        adminTotpSecret=io.artio.api.security.Totp.newSecret();
        jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret,verified,verified_at) VALUES (?,?,TRUE,now())",userId,encryption.encrypt(adminTotpSecret));
        var loginResponse = http.postForEntity("/api/v1/auth/login",
                Map.of("email", "admin-" + userId + "@example.test", "password", "admin-password-123","mfaCode",io.artio.api.TestTotp.code(adminTotpSecret,java.time.Instant.now())), LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).as("Fixture administrator login status").isEqualTo(HttpStatus.OK);
        var login = loginResponse.getBody();
        assertThat(login).isNotNull();
        assertThat(login.accessToken()).isNotBlank();
        adminToken = login.accessToken();
    }

    @Test
    void createsAndReadsProductsFromPostgres() {
        var request = new ProductRequest("Studio Monitor", "27 inch display", "MON-2701");
        var created = adminExchange("/api/v1/products", HttpMethod.POST, request, ProductResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().id()).isNotNull();
        assertThat(created.getBody().active()).isTrue();

        var read = http.getForEntity("/api/v1/products/{id}", ProductResponse.class, created.getBody().id());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).isNotNull();
        assertThat(read.getBody().sku()).isEqualTo("MON-2701");
    }

    @Test
    void persistedAdminPermissionsTakeEffectImmediatelyAcrossAdminDomains() {
        jdbc.update("UPDATE users SET permissions='REPORTS_VIEW' WHERE id=?", adminUserId);

        var reports = adminExchange("/api/v1/admin/reports?days=7", HttpMethod.GET, null, Map.class);
        var usersDenied = adminExchange("/api/v1/admin/users", HttpMethod.GET, null, ApiError.class);
        assertThat(reports.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(usersDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='USER_MANAGE' WHERE id=?", adminUserId);
        var users = adminExchange("/api/v1/admin/users", HttpMethod.GET, null,
                io.artio.api.users.UserController.UserView[].class);
        var reportsDenied = adminExchange("/api/v1/admin/reports?days=7", HttpMethod.GET, null, ApiError.class);
        assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reportsDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='NONE' WHERE id=?", adminUserId);
        var allDenied = adminExchange("/api/v1/admin/users", HttpMethod.GET, null, ApiError.class);
        assertThat(allDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var productCreateDenied = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Restricted item", null, "RESTRICTED-1"), ApiError.class);
        var categoryDenied = adminExchange("/api/v1/categories", HttpMethod.POST, Map.of("name", "Restricted"), ApiError.class);
        var pricingDenied = adminExchange("/api/v1/prices", HttpMethod.POST, Map.of(), ApiError.class);
        assertThat(productCreateDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(categoryDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(pricingDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='PRODUCT_CREATE' WHERE id=?", adminUserId);
        var productCreated = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Restricted item", null, "R-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), ProductResponse.class);
        var productEditDenied = adminExchange("/api/v1/products/{id}", HttpMethod.PUT,
                new ProductRequest("Changed item", null, "UNCHANGED-SKU"), ApiError.class, productCreated.getBody().id());
        var categoryStillDenied = adminExchange("/api/v1/categories", HttpMethod.POST, Map.of("name", "Restricted"), ApiError.class);
        var ordersDenied = adminExchange("/api/v1/admin/orders?page=0&size=10", HttpMethod.GET, null, Map.class);
        assertThat(productCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(productEditDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(categoryStillDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ordersDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='INVENTORY_MANAGE' WHERE id=?", adminUserId);
        var inventoryAllowed = adminExchange("/api/v1/warehouses", HttpMethod.GET, null, Map[].class);
        var productsNoLongerAllowed = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Another item", null, "RESTRICTED-2"), ApiError.class);
        assertThat(inventoryAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(productsNoLongerAllowed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='ORDER_FULFILL' WHERE id=?", adminUserId);
        var ordersAllowed = adminExchange("/api/v1/admin/orders?page=0&size=10", HttpMethod.GET, null, Map.class);
        var inventoryDenied = adminExchange("/api/v1/warehouses", HttpMethod.GET, null, ApiError.class);
        assertThat(ordersAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inventoryDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void extensionAdministrationSeparatesManagementRoutingAndOperationsPermissions() {
        jdbc.update("UPDATE users SET permissions='ANALYTICS_VIEW' WHERE id=?", adminUserId);
        var pluginsDenied = adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, ApiError.class);
        assertThat(pluginsDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='EXTENSION_MANAGE' WHERE id=?", adminUserId);
        var pluginsAllowed = adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, Map[].class);
        var routingDenied = adminExchange("/api/v1/admin/extensions/routing/resolve", HttpMethod.POST,
                Map.of("capabilityId", "catalog.enrichment.v1", "operationName", "enrich", "scope", Map.of(),
                        "pinnedInstanceId", "", "newCommitment", true), ApiError.class);
        var operationsDenied = adminExchange("/api/v1/admin/extensions/events/dead?page=0&size=10", HttpMethod.GET,
                null, ApiError.class);
        assertThat(pluginsAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(routingDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operationsDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='EXTENSION_MANAGE,EXTENSION_ROUTING_VIEW,EXTENSION_OPERATIONS_VIEW' WHERE id=?",
                adminUserId);
        var routingAllowed = adminExchange("/api/v1/admin/extensions/routing/resolve", HttpMethod.POST,
                Map.of("capabilityId", "catalog.enrichment.v1", "operationName", "enrich", "scope", Map.of(),
                        "newCommitment", true), Map.class);
        var operationsAllowed = adminExchange("/api/v1/admin/extensions/events/dead?page=0&size=10", HttpMethod.GET,
                null, Map[].class);
        var replayDenied = adminExchange("/api/v1/admin/extensions/events/" + UUID.randomUUID() + "/replay",
                HttpMethod.POST, Map.of(), ApiError.class);
        assertThat(routingAllowed.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(operationsAllowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET permissions='EXTENSION_OPERATIONS_MANAGE' WHERE id=?", adminUserId);
        var replayAllowedToReachHandler = adminExchange("/api/v1/admin/extensions/events/" + UUID.randomUUID() + "/replay",
                HttpMethod.POST, Map.of(), String.class);
        assertThat(replayAllowedToReachHandler.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superadminAssignsRoleAndPermissionsAtomically() {
        UUID targetId=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,permissions,created_at) VALUES (?,?,?,'Access','Target','CUSTOMER','NONE',now())",
                targetId,"access-"+targetId+"@example.test","fixture-hash");
        jdbc.update("UPDATE users SET role='SUPERADMIN',permissions='*' WHERE id=?",adminUserId);

        var assigned=adminExchange("/api/v1/admin/users/{id}/access",HttpMethod.PUT,
                Map.of("role","ADMIN","permissions",List.of("INVENTORY_MANAGE")),
                io.artio.api.users.UserController.UserView.class,targetId);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody()).isNotNull();
        assertThat(assigned.getBody().role()).isEqualTo("ADMIN");
        assertThat(assigned.getBody().permissions()).containsExactly("INVENTORY_MANAGE");

        var invalid=adminExchange("/api/v1/admin/users/{id}/access",HttpMethod.PUT,
                Map.of("role","ADMIN","permissions",List.of("UNKNOWN_PERMISSION")),ApiError.class,targetId);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT role||':'||permissions FROM users WHERE id=?",String.class,targetId))
                .isEqualTo("ADMIN:INVENTORY_MANAGE");

        var demoted=adminExchange("/api/v1/admin/users/{id}/access",HttpMethod.PUT,
                Map.of("role","CUSTOMER","permissions",List.of("INVENTORY_MANAGE")),
                io.artio.api.users.UserController.UserView.class,targetId);
        assertThat(demoted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(demoted.getBody()).isNotNull();
        assertThat(demoted.getBody().role()).isEqualTo("CUSTOMER");
        assertThat(demoted.getBody().permissions()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT permissions FROM users WHERE id=?",String.class,targetId)).isEqualTo("NONE");

        var selfRoleChange=adminExchange("/api/v1/admin/users/{id}/access",HttpMethod.PUT,
                Map.of("role","ADMIN","permissions",List.of("USER_MANAGE")),ApiError.class,adminUserId);
        assertThat(selfRoleChange.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id=?",String.class,adminUserId)).isEqualTo("SUPERADMIN");
    }

    @Test
    void partialShipmentsTrackLineQuantitiesAndAllocateOnlyWhatActuallyShips() {
        UUID orderId=UUID.randomUUID(),sellableId=UUID.randomUUID(),orderItemId=UUID.randomUUID();
        var warehouse=inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "SHIP-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT),"Shipment test","CH-ZH"));
        inventory.receive(warehouse.id(),sellableId,new BigDecimal("2"));
        inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(orderId,warehouse.id(),sellableId,new BigDecimal("2")));
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) VALUES (?,?,'PAID',20,0,0,20,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",orderId,adminUserId,"partial-ship-"+orderId);
        jdbc.update("INSERT INTO order_items(id,order_id,variant_id,quantity,unit_price,currency,bundle_id) VALUES (?,?,?,2,10,'CHF',NULL)",orderItemId,orderId,sellableId);

        var first=adminExchange("/api/v1/admin/orders/"+orderId+"/shipments",HttpMethod.POST,
                Map.of("trackingNumber","TRACK-"+orderId,"items",List.of(Map.of("orderItemId",orderItemId,"quantity",1))),Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,orderId)).isEqualTo("PARTIALLY_SHIPPED");
        assertThat(inventory.stockByWarehouse(warehouse.id())).singleElement().satisfies(stock->{assertThat(stock.onHand()).isEqualByComparingTo("1");assertThat(stock.reserved()).isEqualByComparingTo("1");});

        var duplicate=adminExchange("/api/v1/admin/orders/"+orderId+"/shipments",HttpMethod.POST,
                Map.of("trackingNumber","TRACK-"+orderId,"items",List.of(Map.of("orderItemId",orderItemId,"quantity",1))),Map.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var second=adminExchange("/api/v1/admin/orders/"+orderId+"/shipments",HttpMethod.POST,
                Map.of("trackingNumber","TRACK-SECOND-"+orderId,"items",List.of(Map.of("orderItemId",orderItemId,"quantity",1))),Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,orderId)).isEqualTo("SHIPPED");
        assertThat(inventory.stockByWarehouse(warehouse.id())).singleElement().satisfies(stock->{assertThat(stock.onHand()).isEqualByComparingTo("0");assertThat(stock.reserved()).isEqualByComparingTo("0");});
    }

    @Test
    void duplicatesProductAsUnpublishedDraftWithFreshIdentityAndSku() throws Exception {
        var source=adminExchange("/api/v1/products",HttpMethod.POST,
                new io.artio.api.catalog.ProductRequest("Desk lamp","Brass lamp","LAMP-ORIGINAL"),
                io.artio.api.catalog.ProductResponse.class);
        assertThat(source.getBody()).isNotNull();
        adminExchange("/api/v1/products/{id}/translations/de",HttpMethod.PUT,
                new io.artio.api.catalog.ProductTranslationApplicationService.TranslationRequest(
                        "Schreibtischleuchte","Messinglampe","source-lamp","Quell-SEO-Titel","Quell-SEO-Text"),
                io.artio.api.catalog.ProductTranslationApplicationService.TranslationView.class,source.getBody().id());
        byte[] sourceImage=testPng(8,6);
        var sourceMedia=uploadProductImage(source.getBody().id(),sourceImage,"Vorderseite");
        assertThat(sourceMedia.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var copy=adminExchange("/api/v1/products/{id}/duplicate",HttpMethod.POST,
                new io.artio.api.catalog.ProductRequest("Desk lamp copy","Brass lamp","LAMP-COPY"),
                io.artio.api.catalog.ProductResponse.class,source.getBody().id());
        assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(copy.getBody()).isNotNull();
        assertThat(copy.getBody().id()).isNotEqualTo(source.getBody().id());
        assertThat(copy.getBody().sku()).isEqualTo("LAMP-COPY");
        assertThat(copy.getBody().name()).isEqualTo("Desk lamp copy");
        assertThat(copy.getBody().active()).isFalse();
        assertThat(copy.getBody().description()).isEqualTo(source.getBody().description());
        var translations=http.getForEntity("/api/v1/products/{id}/translations",
                io.artio.api.catalog.ProductTranslationApplicationService.TranslationView[].class,copy.getBody().id());
        assertThat(translations.getBody()).singleElement().satisfies(translation->{
            assertThat(translation.name()).isEqualTo("Schreibtischleuchte");
            assertThat(translation.description()).isEqualTo("Messinglampe");
            assertThat(translation.seoSlug()).isNull();
            assertThat(translation.seoTitle()).isNull();
        });
        var copiedMedia=http.getForEntity("/api/v1/products/{id}/media",ProductMediaController.MediaResponse[].class,copy.getBody().id());
        assertThat(copiedMedia.getBody()).singleElement().satisfies(media->{
            assertThat(media.id()).isNotEqualTo(sourceMedia.getBody().id());
            assertThat(media.productId()).isEqualTo(copy.getBody().id());
            assertThat(media.altText()).isEqualTo("Vorderseite");
        });
        assertThat(http.getForEntity(copiedMedia.getBody()[0].contentUrl(),byte[].class).getBody()).isEqualTo(sourceImage);
    }

    @Test
    void catalogMetadataAdminManagesHierarchyBrandsAndTypedAttributes(){
        var root=adminExchange("/api/v1/categories",HttpMethod.POST,
                new io.artio.api.catalog.MetadataApplicationService.CategoryRequest("Lighting",null,true),
                io.artio.api.catalog.MetadataApplicationService.CategoryView.class);
        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var child=adminExchange("/api/v1/categories",HttpMethod.POST,
                new io.artio.api.catalog.MetadataApplicationService.CategoryRequest("Table lamps",root.getBody().id(),true),
                io.artio.api.catalog.MetadataApplicationService.CategoryView.class);
        assertThat(child.getBody()).isNotNull();
        var cycle=adminExchange("/api/v1/categories/{id}",HttpMethod.PUT,
                new io.artio.api.catalog.MetadataApplicationService.CategoryRequest("Lighting",child.getBody().id(),true),
                ApiError.class,root.getBody().id());
        assertThat(cycle.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var brand=adminExchange("/api/v1/brands",HttpMethod.POST,
                new io.artio.api.catalog.MetadataApplicationService.BrandRequest("Artio Lighting","Internal brand",true),
                io.artio.api.catalog.MetadataApplicationService.BrandView.class);
        assertThat(brand.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var attribute=adminExchange("/api/v1/attribute-definitions",HttpMethod.POST,
                new io.artio.api.catalog.MetadataApplicationService.AttributeRequest("material_meta","Material",
                        io.artio.catalog.AttributeDefinition.Type.TEXT,false),
                io.artio.api.catalog.MetadataApplicationService.AttributeView.class);
        assertThat(attribute.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var product=adminExchange("/api/v1/products",HttpMethod.POST,new ProductRequest("Table lamp","Brass finish","LMP-META"),ProductResponse.class);
        var assigned=adminExchange("/api/v1/products/{id}/catalog-metadata",HttpMethod.PUT,
                new io.artio.api.catalog.ProductApplicationService.CatalogMetadataRequest(brand.getBody().id(),
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of("material_meta","brass")),ProductResponse.class,product.getBody().id());
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().brandId()).isEqualTo(brand.getBody().id());
        assertThat(assigned.getBody().categoryIds()).contains(root.getBody().id(),child.getBody().id());
        assertThat(assigned.getBody().attributes()).containsEntry("material_meta","brass");

        var inactiveBrand=adminExchange("/api/v1/brands/{id}",HttpMethod.PUT,
                new io.artio.api.catalog.MetadataApplicationService.BrandRequest("Artio Lighting","Internal brand",false),
                io.artio.api.catalog.MetadataApplicationService.BrandView.class,brand.getBody().id());
        assertThat(inactiveBrand.getBody()).isNotNull();
        assertThat(inactiveBrand.getBody().active()).isFalse();
        var disabledAttribute=adminExchange("/api/v1/attribute-definitions/{id}",HttpMethod.PUT,
                new io.artio.api.catalog.MetadataApplicationService.AttributeUpdateRequest("Material composition",false),
                io.artio.api.catalog.MetadataApplicationService.AttributeView.class,attribute.getBody().id());
        assertThat(disabledAttribute.getBody()).isNotNull();
        assertThat(disabledAttribute.getBody().label()).isEqualTo("Material composition");
        assertThat(disabledAttribute.getBody().active()).isFalse();
        var disabledCategory=adminExchange("/api/v1/categories/{id}",HttpMethod.PUT,
                new io.artio.api.catalog.MetadataApplicationService.CategoryRequest("Table lamps",root.getBody().id(),false),
                io.artio.api.catalog.MetadataApplicationService.CategoryView.class,child.getBody().id());
        assertThat(disabledCategory.getBody()).isNotNull();
        assertThat(disabledCategory.getBody().active()).isFalse();
        var updatedWithExistingInactiveMetadata=adminExchange("/api/v1/products/{id}/catalog-metadata",HttpMethod.PUT,
                new io.artio.api.catalog.ProductApplicationService.CatalogMetadataRequest(brand.getBody().id(),
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of("material_meta","brass")),ProductResponse.class,product.getBody().id());
        assertThat(updatedWithExistingInactiveMetadata.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updatedWithExistingInactiveMetadata.getBody().categoryIds()).contains(child.getBody().id());
        var removedInactiveValue=adminExchange("/api/v1/products/{id}/catalog-metadata",HttpMethod.PUT,
                new io.artio.api.catalog.ProductApplicationService.CatalogMetadataRequest(brand.getBody().id(),
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of()),ApiError.class,product.getBody().id());
        assertThat(removedInactiveValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatesProductDescriptionAndActivationStateWithoutChangingItsSku() {
        var created = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Studio Display", "Original description", "DSP-2701"), ProductResponse.class);
        assertThat(created.getBody()).isNotNull();

        var updated = adminExchange("/api/v1/products/{id}", HttpMethod.PUT,
                new UpdateProductRequest("Studio Display X", "Updated"), ProductResponse.class, created.getBody().id());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().name()).isEqualTo("Studio Display X");
        assertThat(updated.getBody().sku()).isEqualTo("DSP-2701");

        var inactive = adminExchange("/api/v1/products/{id}/active", HttpMethod.PUT,
                new SetActiveRequest(false), ProductResponse.class, created.getBody().id());
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void storesTranslationsIndependentlyAndResolvesLocaleFallbackPerField() {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Coffee", "Base description", "COF-100"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();
        UUID id = product.getBody().id();

        adminExchange("/api/v1/products/{id}/translations/{locale}", HttpMethod.PUT,
                new ProductTranslationApplicationService.TranslationRequest("Kaffee", null, "kaffee", "Kaffee kaufen", "Frisch gerösteter Kaffee."),
                ProductTranslationApplicationService.TranslationView.class, id, "de");
        adminExchange("/api/v1/products/{id}/translations/{locale}", HttpMethod.PUT,
                new ProductTranslationApplicationService.TranslationRequest("Coffee", "Roasted beans", "coffee", "Buy coffee", "Freshly roasted coffee."),
                ProductTranslationApplicationService.TranslationView.class, id, "en");

        var localized = http.getForEntity("/api/v1/products/{id}/localized?locale=de",
                ProductTranslationApplicationService.LocalizedProductView.class, id);
        assertThat(localized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localized.getBody()).isNotNull();
        assertThat(localized.getBody().name()).isEqualTo("Kaffee");
        assertThat(localized.getBody().description()).isEqualTo("Roasted beans");
        assertThat(localized.getBody().seoSlug()).isEqualTo("kaffee");
        assertThat(localized.getBody().seoTitle()).isEqualTo("Kaffee kaufen");
        assertThat(localized.getBody().sku()).isEqualTo("COF-100");

        var fallback = http.getForEntity("/api/v1/products/{id}/localized?locale=fr",
                ProductTranslationApplicationService.LocalizedProductView.class, id);
        assertThat(fallback.getBody()).isNotNull();
        assertThat(fallback.getBody().requestedLocale()).isEqualTo("fr");
        assertThat(fallback.getBody().seoSlug()).isEqualTo("coffee");

        var translations = http.getForEntity("/api/v1/products/{id}/translations",
                ProductTranslationApplicationService.TranslationView[].class, id);
        assertThat(translations.getBody()).hasSize(2);
        assertThat(translations.getBody()).anySatisfy(translation -> {
            assertThat(translation.locale()).isEqualTo("de");
            assertThat(translation.seoSlug()).isEqualTo("kaffee");
        });
        var canonical = http.getForEntity("/api/v1/products/{id}", ProductResponse.class, id);
        assertThat(canonical.getBody()).isNotNull();
        assertThat(canonical.getBody().name()).isEqualTo("Coffee");
    }

    @Test
    void validatesExtensionManifestOnlyForAdministratorsAndNeverExecutesImage() {
        var manifest = new io.artio.extensions.PluginManifest("1", "payments.example", "Example Payments", "Artio",
                "1.0.0", "Example provider", "0.1.0", "1.0.0",
                "ghcr.io/artio/example-payments@sha256:" + "a".repeat(64), java.util.List.of(),
                java.util.List.of("payment.process"), java.util.Map.of(), java.util.List.of("payment.provider.v1"),
                "/health", "/diagnostics");

        var anonymous = http.postForEntity("/api/v1/admin/extensions/validate", manifest, ApiError.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var validation = adminExchange("/api/v1/admin/extensions/validate", HttpMethod.POST, manifest,
                io.artio.api.extensions.ExtensionManagementController.ManifestValidationResponse.class);
        assertThat(validation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validation.getBody()).isNotNull();
        assertThat(validation.getBody().valid()).as("Core manifest validation errors: %s", validation.getBody().errors())
                .isTrue();

        var mutable = new io.artio.extensions.PluginManifest("1", "payments.example", "Example Payments", "Artio",
                "1.0.0", "Example provider", "0.1.0", "1.0.0", "ghcr.io/artio/example-payments:latest",
                java.util.List.of(), java.util.List.of("host-access"), java.util.Map.of(), java.util.List.of("payment.provider.v1"),
                "/health", "/diagnostics");
        var rejected = adminExchange("/api/v1/admin/extensions/validate", HttpMethod.POST, mutable,
                io.artio.api.extensions.ExtensionManagementController.ManifestValidationResponse.class);
        assertThat(rejected.getBody()).isNotNull();
        assertThat(rejected.getBody().valid()).isFalse();
        assertThat(rejected.getBody().errors()).contains("image must be pinned by sha256 digest", "unsupported permission: host-access");
    }

    @Test
    void importsPublishedMarketplaceManifestThroughCoreApiWhenAcceptanceIsConfigured() throws Exception {
        String marketplacePluginId = System.getenv("ARTIO_MARKETPLACE_TEST_PLUGIN_ID");
        String marketplaceVersion = System.getenv("ARTIO_MARKETPLACE_TEST_PLUGIN_VERSION");
        String marketplaceTrustedKeys = System.getenv("ARTIO_MARKETPLACE_TRUSTED_KEYS");
        assumeTrue(marketplacePluginId != null && !marketplacePluginId.isBlank()
                && marketplaceVersion != null && !marketplaceVersion.isBlank()
                && System.getenv("ARTIO_MARKETPLACE_BASE_URL") != null
                && marketplaceTrustedKeys != null && !marketplaceTrustedKeys.isBlank(),
                "Set Marketplace URL, release identity and trusted signing key for live signed-descriptor acceptance");

        var listing = adminExchange("/api/v1/admin/extensions/marketplace/plugins?q=reference",
                HttpMethod.GET, null, Map.class);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getBody()).isNotNull();
        assertThat(listing.getBody().get("content").toString()).contains(marketplacePluginId);

        String idempotencyKey = "marketplace-" + UUID.randomUUID();
        var requested = adminExchange("/api/v1/admin/extensions/marketplace/plugins/{id}/releases/{version}/installations",
                HttpMethod.POST, Map.of("idempotencyKey", idempotencyKey), Map.class, marketplacePluginId, marketplaceVersion);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requested.getBody()).isNotNull();
        assertThat(requested.getBody()).containsEntry("pluginId", marketplacePluginId)
                .containsEntry("version", marketplaceVersion);
        String descriptorDigest = (String) requested.getBody().get("descriptorDigest");
        assertThat(descriptorDigest).matches("[a-f0-9]{64}");
        @SuppressWarnings("unchecked") Map<String, Object> job = (Map<String, Object>) requested.getBody().get("job");
        assertThat(job).containsEntry("pluginId", marketplacePluginId)
                .containsEntry("sourceKind", "MARKETPLACE")
                .containsEntry("releaseDigest", descriptorDigest);
        String jobId = String.valueOf(job.get("jobId"));
        var replay = adminExchange("/api/v1/admin/extensions/marketplace/plugins/{id}/releases/{version}/installations",
                HttpMethod.POST, Map.of("idempotencyKey", idempotencyKey), Map.class, marketplacePluginId, marketplaceVersion);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        @SuppressWarnings("unchecked") Map<String, Object> replayJob = (Map<String, Object>) replay.getBody().get("job");
        assertThat(replayJob.get("jobId")).isEqualTo(jobId);

        List<String> downloadedImages = new java.util.ArrayList<>();
        downloadedImages.add(jdbc.queryForObject("SELECT image_reference FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                String.class, marketplacePluginId, marketplaceVersion));
        downloadedImages.addAll(jdbc.queryForList("SELECT service->>'image' FROM extension_plugin_releases r "
                + "CROSS JOIN LATERAL jsonb_array_elements(r.manifest->'services') service WHERE r.plugin_id=? AND r.version=?",
                String.class, marketplacePluginId, marketplaceVersion));
        var dockerConfig = com.github.dockerjava.core.DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var dockerTransport = new com.github.dockerjava.httpclient5.ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost()).sslConfig(dockerConfig.getSSLConfig()).maxConnections(6)
                .connectionTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(120)).build();
        com.github.dockerjava.api.DockerClient docker = null;
        io.artio.api.extensions.DockerExtensionRuntime liveRuntime = null;
        try {
            docker = com.github.dockerjava.core.DockerClientImpl.getInstance(dockerConfig, dockerTransport);
            liveRuntime = new io.artio.api.extensions.DockerExtensionRuntime(docker, "artio-extensions", 8080,
                    new io.artio.api.security.ExtensionCredentialService(ACTIVE_TEST_ENCRYPTION_KEY),
                    "http://extension-egress:3128");
            @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<io.artio.extensions.ExtensionRuntimePort>
                    runtimeProvider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito.when(runtimeProvider.getIfAvailable()).thenReturn(liveRuntime);
            var worker = new io.artio.api.extensions.ExtensionInstallationJobWorker(jdbc,
                    new com.fasterxml.jackson.databind.ObjectMapper(), runtimeProvider, extensionRegistryCredentials,
                    ociArchiveInspector, archiveQuarantineStore, 1_800_000, 300_000);
            worker.runPending();
            var completed = adminExchange("/api/v1/admin/extensions/installations/{jobId}", HttpMethod.GET,
                    null, io.artio.api.extensions.ExtensionInstallationJobOperations.JobView.class, jobId);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(completed.getBody()).isNotNull();
            assertThat(completed.getBody().state()).isEqualTo("INSTALLED");
            String v2ManifestJson = jdbc.queryForObject("SELECT manifest::text FROM extension_plugin_v2_releases WHERE plugin_id=?",
                    String.class, marketplacePluginId);
            var v2Manifest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(v2ManifestJson);
            String v2Image = v2Manifest.path("packageManifest").path("image").asText();
            assertThat(v2Manifest.path("packageManifest").path("manifestVersion").asText()).isEqualTo("2");
            assertThat(v2Image).isEqualTo(System.getenv("ARTIO_TEST_EXTENSION_IMAGE_V2"));
            String manifestJson = jdbc.queryForObject("SELECT manifest::text FROM extension_plugin_releases WHERE plugin_id=? AND version=?",
                    String.class, marketplacePluginId, marketplaceVersion);
            var installedManifest = new com.fasterxml.jackson.databind.ObjectMapper().readValue(manifestJson,
                    io.artio.extensions.PluginManifest.class);
            assertThat(installedManifest.manifestVersion()).isEqualTo("1");
            assertThat(installedManifest.image()).isEqualTo(v2Image);
            docker.inspectImageCmd(installedManifest.image()).exec();
        } finally {
            try {
                if (liveRuntime != null) {
                    try { liveRuntime.disable(marketplacePluginId); }
                    finally { liveRuntime.uninstall(marketplacePluginId); }
                }
                var removed = adminExchange("/api/v1/admin/extensions/{id}", HttpMethod.DELETE, null, Map.class, marketplacePluginId);
                assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(removed.getBody()).containsEntry("lifecycleState", "UNINSTALLED");
            } finally {
                if (docker != null) {
                    try {
                        for (String image : downloadedImages) {
                            try { docker.removeImageCmd(image).exec(); }
                            catch (com.github.dockerjava.api.exception.NotFoundException alreadyMissing) { }
                        }
                    } finally {
                        docker.close();
                    }
                }
            }
        }
    }

    @Test
    void catalogSearchRebuildRequiresAdminAndReportsDisabledSearch() {
        var anonymous = http.postForEntity("/api/v1/admin/catalog/search-index/rebuild", null, ApiError.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var disabled = adminExchange("/api/v1/admin/catalog/search-index/rebuild", HttpMethod.POST, null, ApiError.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(disabled.getBody()).isNotNull();
        assertThat(disabled.getBody().status()).isEqualTo(503);
    }

    @Test
    void registersExtensionValidatesAndEncryptsSecretsAndAuditsWithoutReturningSecretValues() throws Exception {
        var manifest = new io.artio.extensions.PluginManifest("1", "payments.integration", "Integration Payments", "Artio",
                "1.0.0", "Integration provider", "0.1.0", "1.0.0",
                "ghcr.io/artio/integration-payments@sha256:" + "b".repeat(64), java.util.List.of(),
                java.util.List.of("payment.process"), java.util.Map.of(
                        "apiKey", new io.artio.extensions.PluginManifest.ConfigField("secret", true, true, "API key", "Provider secret", null, java.util.List.of(), null, null, null, null),
                        "mode", new io.artio.extensions.PluginManifest.ConfigField("enum", true, false, "Mode", null, null, java.util.List.of("test", "live"), null, null, null, null),
                        "endpoint", new io.artio.extensions.PluginManifest.ConfigField("url", true, false, "Endpoint", null, null, java.util.List.of(), null, null, null, null)),
                java.util.List.of("payment.provider.v1"), "/health", "/diagnostics");
        var registered = adminExchange("/api/v1/admin/extensions/register", HttpMethod.POST, manifest,
                io.artio.api.extensions.ExtensionManagementController.PluginView.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();
        assertThat(registered.getBody().lifecycleState()).isEqualTo("VALIDATED");
        var instance = jdbc.queryForMap("SELECT instance_key,compatibility_mode,runtime_identity,configuration_generation FROM extension_plugin_instances WHERE plugin_id=?", manifest.id());
        assertThat(instance.get("instance_key")).isEqualTo("legacy-default");
        assertThat(instance.get("compatibility_mode")).isEqualTo("LEGACY_V1");
        assertThat(instance.get("runtime_identity")).isEqualTo(manifest.id());

        String secret = "sk_test_write_only_integration_secret";
        var configured = adminExchange("/api/v1/admin/extensions/{id}/configuration", HttpMethod.PUT,
                Map.of("apiKey", secret, "mode", "test", "endpoint", "https://provider.example.test"),
                io.artio.api.extensions.ExtensionManagementController.PluginView.class, manifest.id());
        assertThat(configured.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(configured.getBody()).isNotNull();
        assertThat(configured.getBody().configuredSecrets()).contains("apiKey");
        assertThat(mapper.writeValueAsString(configured.getBody())).doesNotContain(secret);
        String ciphertext = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugins WHERE plugin_id=?", String.class, manifest.id());
        assertThat(ciphertext).isNotBlank().doesNotContain(secret);
        assertThat(encryption.decrypt(ciphertext)).isEqualTo(secret);

        var updated = adminExchange("/api/v1/admin/extensions/{id}/configuration", HttpMethod.PUT,
                Map.of("mode", "live", "endpoint", "https://provider.example.test"),
                io.artio.api.extensions.ExtensionManagementController.PluginView.class, manifest.id());
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().configurationVersion()).isEqualTo(2);
        assertThat(updated.getBody().configuredSecrets()).contains("apiKey");
        assertThat(jdbc.queryForObject("SELECT configuration_generation FROM extension_plugin_instances WHERE plugin_id=?", Integer.class, manifest.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugin_instances WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo(ciphertext);
        var audit = adminExchange("/api/v1/admin/extensions/{id}/audit", HttpMethod.GET, null,
                io.artio.api.extensions.ExtensionManagementController.AuditView[].class, manifest.id());
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.getBody()).hasSize(3);
        assertThat(mapper.writeValueAsString(audit.getBody())).doesNotContain(secret);

        var invalid = adminExchange("/api/v1/admin/extensions/{id}/configuration", HttpMethod.PUT,
                Map.of("mode", "root", "endpoint", "javascript:alert(1)"),
                io.artio.api.extensions.ExtensionManagementController.ConfigurationErrorResponse.class, manifest.id());
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var uninstalled = adminExchange("/api/v1/admin/extensions/{id}", HttpMethod.DELETE, null, Map.class, manifest.id());
        assertThat(uninstalled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uninstalled.getBody()).containsEntry("lifecycleState", "UNINSTALLED");
        assertThat(jdbc.queryForObject("SELECT encrypted_secrets::text FROM extension_plugins WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo("UNINSTALLED");
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo("UNINSTALLED");
        assertThat(jdbc.queryForObject("SELECT encrypted_secrets::text FROM extension_plugin_instances WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo("{}");
        assertThat(adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, Map[].class).getBody())
                .noneMatch(plugin -> manifest.id().equals(plugin.get("id")));

        var restored = adminExchange("/api/v1/admin/extensions/{id}/restore", HttpMethod.POST, Map.of(),
                io.artio.api.extensions.ExtensionManagementController.PluginView.class, manifest.id());
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getBody()).isNotNull();
        assertThat(restored.getBody().lifecycleState()).isEqualTo("VALIDATED");
        assertThat(restored.getBody().configuredSecrets()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE plugin_id=?", String.class, manifest.id()))
                .isEqualTo("CONFIGURED");
        assertThat(adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, Map[].class).getBody())
                .anyMatch(plugin -> manifest.id().equals(plugin.get("id")));
    }

    @Test
    void pluginDiscoveryFiltersGeographicAvailabilityButDoesNotRestrictRegistration() {
        var swissPost = new io.artio.extensions.PluginManifest("1", "shipping.swiss-post", "Swiss Post", "Artio",
                "1.0.0", "Swiss carrier adapter", "0.1.0", "1.0.0",
                "ghcr.io/artio/swiss-post@sha256:" + "e".repeat(64), java.util.List.of(),
                java.util.List.of("shipping.quote"), java.util.Map.of(), java.util.List.of("shipping.provider.v1"),
                "/health", "/diagnostics", new io.artio.extensions.PluginManifest.GeographicAvailability("COUNTRY_SPECIFIC", java.util.List.of("CH")));
        var global = new io.artio.extensions.PluginManifest("1", "observability.prometheus", "Prometheus", "Artio",
                "1.0.0", "Global metrics plugin", "0.1.0", "1.0.0",
                "ghcr.io/artio/prometheus@sha256:" + "f".repeat(64), java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), java.util.List.of(), "/health", "/diagnostics");
        assertThat(adminExchange("/api/v1/admin/extensions/register", HttpMethod.POST, swissPost, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(adminExchange("/api/v1/admin/extensions/register", HttpMethod.POST, global, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        var ch = adminExchange("/api/v1/admin/extensions?country=CH", HttpMethod.GET, null, Map[].class).getBody();
        var de = adminExchange("/api/v1/admin/extensions?country=DE", HttpMethod.GET, null, Map[].class).getBody();
        assertThat(ch).extracting(plugin -> plugin.get("id")).contains("shipping.swiss-post", "observability.prometheus");
        assertThat(de).extracting(plugin -> plugin.get("id")).doesNotContain("shipping.swiss-post").contains("observability.prometheus");

        var catalog = adminExchange("/api/v1/admin/extensions/catalog?country=CH", HttpMethod.GET, null, Map[].class).getBody();
        assertThat(catalog).extracting(plugin -> plugin.get("pluginId"))
                .contains("payments.stripe", "payments.twint", "shipping.swiss-post");
        var registeredRecommendation = java.util.Arrays.stream(catalog)
                .filter(plugin -> "shipping.swiss-post".equals(plugin.get("pluginId"))).findFirst().orElseThrow();
        assertThat(registeredRecommendation.get("releaseRegistered")).isEqualTo(true);
        assertThat(registeredRecommendation.get("status")).isEqualTo("CONFIGURATION_REQUIRED");
        var unavailableRecommendation = java.util.Arrays.stream(catalog)
                .filter(plugin -> "payments.stripe".equals(plugin.get("pluginId"))).findFirst().orElseThrow();
        assertThat(unavailableRecommendation.get("releaseRegistered")).isEqualTo(false);
        assertThat(unavailableRecommendation.get("status")).isEqualTo("WAITING_FOR_RELEASE");

        var invalid = adminExchange("/api/v1/admin/extensions?country=XX", HttpMethod.GET, null, ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void registersV2ReleasePersistsExplicitBindingAndRoutesOnlyEnabledDeclaredInstances() throws Exception {
        String pluginId = "test.v2-payment-" + UUID.randomUUID().toString().substring(0, 8);
        var packageManifest = new io.artio.extensions.PluginManifest("2", pluginId, "V2 Payment", "Artio",
                "1.0.0", "Capability-based payment test plugin", "0.1.0", "9.0.0",
                "ghcr.io/artio/test-payment@sha256:" + "a".repeat(64), List.of(), List.of(),
                Map.of("account", new io.artio.extensions.PluginManifest.ConfigField("text", false, false,
                                "Account", "Merchant account label", null, List.of(), null, 120, null, null),
                        "apiKey", new io.artio.extensions.PluginManifest.ConfigField("secret", false, true,
                                "API key", "Merchant credential", null, List.of(), null, 200, null, null)), List.of(),
                "/health", "/diagnostics", io.artio.extensions.PluginManifest.GeographicAvailability.global(),
                List.of(), List.of(), Map.of("ARTIO_MERCHANT_ACCOUNT", "account", "ARTIO_API_KEY", "apiKey"));
        var v2 = new io.artio.extensions.PluginManifestV2(packageManifest, List.of(
                new io.artio.extensions.PluginManifestV2.CapabilityDeclaration("payment.provider.v1",
                        List.of("listMethods", "createCheckout", "capture", "verifyWebhook"),
                        List.of("payments.methods.read", "payment.process"), Map.of()),
                new io.artio.extensions.PluginManifestV2.CapabilityDeclaration("catalog.enrichment.v1",
                        List.of("enrich"), List.of("catalog.read"), Map.of()),
                new io.artio.extensions.PluginManifestV2.CapabilityDeclaration("storage.object.v1",
                        List.of("put", "get", "delete"), List.of("storage.object.write", "storage.object.read", "storage.object.delete"), Map.of())),
                List.of(new io.artio.extensions.PluginManifestV2.EventSubscription("order.paid", 1, List.of())));

        assertThat(http.postForEntity("/api/v1/admin/extensions/register-v2", v2, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var registered = adminExchange("/api/v1/admin/extensions/register-v2", HttpMethod.POST, v2, Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID instanceId = jdbc.queryForObject("SELECT instance_id FROM extension_plugin_instances WHERE plugin_id=?", UUID.class, pluginId);
        Long v2ReleaseId = jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances WHERE instance_id=?", Long.class, instanceId);
        assertThat(jdbc.queryForObject("SELECT compatibility_mode FROM extension_plugin_instances WHERE instance_id=?", String.class, instanceId))
                .isEqualTo("CAPABILITY_V2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_plugin_v2_releases v JOIN extension_plugin_instances i USING(release_id) WHERE i.instance_id=?",
                Integer.class, instanceId)).isEqualTo(1);
        var configuredDefault = adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}/configuration", HttpMethod.PUT,
                Map.of("account", "CH", "apiKey", "default-private-key"), Map.class, pluginId, instanceId);
        assertThat(configuredDefault.getStatusCode()).isEqualTo(HttpStatus.OK);
        var secondInstance = adminExchange("/api/v1/admin/extensions/{id}/instances", HttpMethod.POST,
                Map.of("instanceKey", "account-de", "configuration", Map.of("account", "DE", "apiKey", "second-private-key")),
                Map.class, pluginId);
        assertThat(secondInstance.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID secondInstanceId = UUID.fromString(secondInstance.getBody().get("instanceId").toString());
        assertThat(secondInstanceId).isNotEqualTo(instanceId);
        assertThat(jdbc.queryForObject("SELECT release_id FROM extension_plugin_instances WHERE instance_id=?", Long.class, secondInstanceId))
                .isEqualTo(v2ReleaseId);
        assertThat(jdbc.queryForObject("SELECT runtime_identity FROM extension_plugin_instances WHERE instance_id=?", String.class, secondInstanceId))
                .isNotEqualTo(jdbc.queryForObject("SELECT runtime_identity FROM extension_plugin_instances WHERE instance_id=?", String.class, instanceId));
        String defaultSecret = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugin_instances WHERE instance_id=?",
                String.class, instanceId);
        String secondSecret = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugin_instances WHERE instance_id=?",
                String.class, secondInstanceId);
        assertThat(defaultSecret).isNotEqualTo(secondSecret);
        assertThat(encryption.decrypt(defaultSecret)).isEqualTo("default-private-key");
        assertThat(encryption.decrypt(secondSecret)).isEqualTo("second-private-key");
        var instances = adminExchange("/api/v1/admin/extensions/{id}/instances", HttpMethod.GET, null, Map[].class, pluginId);
        assertThat(instances.getBody()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_event_subscriptions WHERE instance_id=? AND event_type='order.paid'",
                Integer.class, secondInstanceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_event_subscriptions WHERE instance_id=? AND event_type='order.paid' AND permissions_snapshot='[]'::jsonb",
                Integer.class, instanceId)).isEqualTo(1);
        UUID subscriptionId = jdbc.queryForObject("SELECT subscription_id FROM extension_event_subscriptions WHERE instance_id=?", UUID.class, instanceId);
        var paused = adminExchange("/api/v1/admin/extensions/{id}/subscriptions/{subscriptionId}/state", HttpMethod.PUT,
                Map.of("state", "PAUSED"), Map.class, pluginId, subscriptionId);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT subscription_state FROM extension_event_subscriptions WHERE subscription_id=?", String.class, subscriptionId))
                .isEqualTo("PAUSED");
        var subscriptions = adminExchange("/api/v1/admin/extensions/{id}/subscriptions", HttpMethod.GET, null, Map[].class, pluginId);
        assertThat(subscriptions.getBody()).hasSize(2);
        assertThat(java.util.Arrays.stream(subscriptions.getBody())
                .filter(subscription -> subscriptionId.toString().equals(subscription.get("subscriptionId")))
                .findFirst().orElseThrow()).containsEntry("subscriptionState", "PAUSED");

        var binding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                        "scope", Map.of("country", "CH"), "priority", 20), Map.class, pluginId);
        assertThat(binding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_capability_bindings WHERE instance_id=?", Integer.class, instanceId)).isEqualTo(1);
        UUID bindingId = UUID.fromString(binding.getBody().get("bindingId").toString());
        assertThat(adminExchange("/api/v1/admin/extensions/{id}/bindings/{bindingId}/state", HttpMethod.PUT,
                Map.of("state", "DISABLED"), Map.class, pluginId, bindingId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT eligibility_state FROM extension_capability_bindings WHERE binding_id=?", String.class, bindingId))
                .isEqualTo("DISABLED");
        assertThat(adminExchange("/api/v1/admin/extensions/{id}/bindings/{bindingId}/state", HttpMethod.PUT,
                Map.of("state", "ACTIVE"), Map.class, pluginId, bindingId).getStatusCode()).isEqualTo(HttpStatus.OK);
        var bindings = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.GET, null, Map[].class, pluginId);
        assertThat(bindings.getBody()).hasSize(1);
        var undeclared = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "refund", "scope", Map.of(), "priority", 0),
                Map.class, pluginId);
        assertThat(undeclared.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var secondBinding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                        "scope", Map.of("country", "DE"), "priority", 20, "instanceId", secondInstanceId), Map.class, pluginId);
        assertThat(secondBinding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (UUID paymentInstance : List.of(instanceId, secondInstanceId)) {
            assertThat(adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                    Map.of("capabilityId", "payment.provider.v1", "operationName", "listMethods",
                            "scope", Map.of(), "priority", 0, "instanceId", paymentInstance), Map.class, pluginId).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        var aggregateRuntime = org.mockito.Mockito.mock(io.artio.extensions.ExtensionRuntimePort.class);
        var aggregateCalls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        java.util.concurrent.atomic.AtomicBoolean providerUnavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var aggregateArrivals = new java.util.concurrent.CountDownLatch(2);
        var aggregateInFlight = new java.util.concurrent.atomic.AtomicInteger();
        var aggregatePeakConcurrency = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(aggregateRuntime.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq("payment.provider.v1"), org.mockito.ArgumentMatchers.eq("listMethods"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenAnswer(call -> {
            String runtimeIdentity = call.getArgument(0);
            aggregateCalls.add(runtimeIdentity);
            int inFlight = aggregateInFlight.incrementAndGet();
            aggregatePeakConcurrency.accumulateAndGet(inFlight, Math::max);
            try {
                aggregateArrivals.countDown();
                aggregateArrivals.await(1, TimeUnit.SECONDS);
                return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(
                        providerUnavailable.get() && runtimeIdentity.endsWith(secondInstanceId.toString().replace("-", "")) ? 503 : 200,
                        "{\"methods\":[{\"methodId\":\"card\",\"displayName\":\"Card\",\"countries\":[\"CH\"],\"currencies\":[\"CHF\"],\"hostedCheckout\":true}]}");
            } finally { aggregateInFlight.decrementAndGet(); }
        });
        var aggregateBeans = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("aggregateRuntime", aggregateRuntime));
        var aggregateInvoker = new io.artio.api.extensions.CommerceCapabilityInvoker(jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(), encryption,
                aggregateBeans.getBeanProvider(io.artio.extensions.ExtensionRuntimePort.class));
        var exclusiveBinding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "catalog.enrichment.v1", "operationName", "enrich",
                        "scope", Map.of("country", "CH"), "priority", 20), Map.class, pluginId);
        assertThat(exclusiveBinding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var overlappingBinding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "catalog.enrichment.v1", "operationName", "enrich",
                        "scope", Map.of("currency", "CHF"), "priority", 20, "instanceId", secondInstanceId), Map.class, pluginId);
        assertThat(overlappingBinding.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_capability_bindings b "
                        + "JOIN extension_plugin_instances i ON i.instance_id=b.instance_id "
                        + "WHERE i.plugin_id=? AND b.capability_id='catalog.enrichment.v1' AND b.operation_name='enrich'",
                Integer.class, pluginId)).isEqualTo(1);
        var webhookBinding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "verifyWebhook",
                        "scope", Map.of("country", "CH"), "priority", 20), Map.class, pluginId);
        assertThat(webhookBinding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (UUID storageInstance : List.of(instanceId, secondInstanceId)) {
            var storageBinding = adminExchange("/api/v1/admin/extensions/{id}/bindings", HttpMethod.POST,
                    Map.of("capabilityId", "storage.object.v1", "operationName", "put", "scope", Map.of(),
                            "priority", 5, "instanceId", storageInstance), Map.class, pluginId);
            assertThat(storageBinding.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        String protectedMediaKey = UUID.randomUUID() + ".png";
        jdbc.update("INSERT INTO catalog_media_storage_objects(storage_key,content_checksum,byte_length,primary_instance_id,"
                        + "primary_release_id,primary_configuration_generation,replica_instance_ids,provider_versions) "
                        + "VALUES (?, ?, 1, ?, ?, 1, ?::jsonb, '{}'::jsonb)", protectedMediaKey, "a".repeat(64), instanceId,
                v2ReleaseId, "[\"" + secondInstanceId + "\"]");
        assertThat(adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}", HttpMethod.DELETE, null, Map.class,
                pluginId, instanceId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("DELETE FROM catalog_media_storage_objects WHERE storage_key=?", protectedMediaKey);

        var unavailable = adminExchange("/api/v1/admin/extensions/routing/resolve", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                        "scope", Map.of("country", "CH"), "newCommitment", true), ApiError.class);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='ENABLED' WHERE instance_id=?", instanceId);
        var plan = adminExchange("/api/v1/admin/extensions/routing/resolve", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                        "scope", Map.of("country", "CH"), "newCommitment", true), io.artio.extensions.CapabilityRouter.RoutePlan.class);
        assertThat(plan.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(plan.getBody()).isNotNull();
        assertThat(plan.getBody().instanceIds()).containsExactly(instanceId);
        assertThat(plan.getBody().policy()).isEqualTo(io.artio.extensions.CapabilityRegistry.Policy.SELECT_ONE);
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='ENABLED' WHERE instance_id=?", secondInstanceId);
        var secondPlan = adminExchange("/api/v1/admin/extensions/routing/resolve", HttpMethod.POST,
                Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                        "scope", Map.of("country", "DE"), "newCommitment", true), io.artio.extensions.CapabilityRouter.RoutePlan.class);
        assertThat(secondPlan.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondPlan.getBody().instanceIds()).containsExactly(secondInstanceId);
        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED' WHERE plugin_id=?", pluginId);

        org.mockito.Mockito.when(aggregateRuntime.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq("payment.provider.v1"), org.mockito.ArgumentMatchers.eq("createCheckout"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenAnswer(call -> {
            int frozenAttempts = jdbc.queryForObject("SELECT count(*) FROM extension_operation_attempts WHERE capability_id='payment.provider.v1' "
                    + "AND operation_name='createCheckout' AND instance_id=? AND attempt_state='IN_PROGRESS'", Integer.class, instanceId);
            assertThat(frozenAttempts).isEqualTo(1);
            assertThat((String) call.getArgument(6)).contains("\"paymentMethodId\":\"card\"");
            return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(200,
                    "{\"providerReference\":\"selected-session\",\"checkoutUrl\":\"https://pay.example.test/selected\",\"state\":\"PENDING\"}");
        });
        var selectedCheckout = aggregateInvoker.invokeSelected("payment.provider.v1", "createCheckout", Map.of("country", "CH", "currency", "CHF"),
                instanceId, "attempt-selected", Map.of("orderId", "order-selected", "paymentMethodId", "card"));
        assertThat(selectedCheckout.owner().instanceId()).isEqualTo(instanceId);
        assertThat(selectedCheckout.response().path("providerReference").asText()).isEqualTo("selected-session");
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM extension_operation_attempts WHERE capability_id='payment.provider.v1' "
                + "AND operation_name='createCheckout' AND idempotency_key='attempt-selected'", String.class)).isEqualTo("SUCCEEDED");

        var aggregate = aggregateInvoker.aggregateRead("payment.provider.v1", "listMethods", Map.of("country", "CH"), Map.of("country", "CH", "currency", "CHF"));
        assertThat(aggregate.results()).hasSize(2);
        assertThat(aggregate.failures()).isEmpty();
        assertThat(aggregate.results()).extracting(io.artio.api.extensions.CommerceCapabilityInvoker.AggregateProviderResult::instanceId)
                .containsExactlyInAnyOrder(instanceId, secondInstanceId);
        assertThat(aggregateCalls).containsExactlyInAnyOrder(jdbc.queryForObject("SELECT runtime_identity FROM extension_plugin_instances WHERE instance_id=?", String.class, instanceId),
                jdbc.queryForObject("SELECT runtime_identity FROM extension_plugin_instances WHERE instance_id=?", String.class, secondInstanceId));
        assertThat(aggregatePeakConcurrency.get()).isEqualTo(2);
        providerUnavailable.set(true);
        var partial = aggregateInvoker.aggregateRead("payment.provider.v1", "listMethods", Map.of("country", "CH"), Map.of("country", "CH", "currency", "CHF"));
        assertThat(partial.results()).hasSize(1);
        assertThat(partial.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.instanceId()).isEqualTo(secondInstanceId);
            assertThat(failure.errorCode()).isEqualTo("HTTP_503");
        });

        var fakeRuntime = org.mockito.Mockito.mock(io.artio.extensions.ExtensionRuntimePort.class);
        org.mockito.Mockito.when(fakeRuntime.healthInstance(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(io.artio.extensions.PluginManifest.class)))
                .thenReturn(new io.artio.extensions.ExtensionRuntimePort.RuntimeHealth(true, "HEALTHY"));
        var storageObjects = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, byte[]>>();
        var pendingChunks = new java.util.concurrent.ConcurrentHashMap<String, java.io.ByteArrayOutputStream>();
        org.mockito.Mockito.when(fakeRuntime.invoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq("storage.object.v1"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenAnswer(call -> {
            String runtimeIdentity = call.getArgument(0);
            String operation = call.getArgument(3);
            var request = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) call.getArgument(6));
            String objectKey = request.path("key").asText();
            if ("put".equals(operation)) {
                String uploadId = request.path("uploadId").asText();
                String chunkKey = runtimeIdentity + ":" + uploadId;
                var stream = pendingChunks.computeIfAbsent(chunkKey, ignored -> new java.io.ByteArrayOutputStream());
                stream.writeBytes(java.util.Base64.getDecoder().decode(request.path("chunkBase64").asText()));
                byte[] content = stream.toByteArray();
                if (request.path("complete").asBoolean()) {
                    String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                    if (!checksum.equals(request.path("sha256").asText())) return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(422, "{}");
                    storageObjects.computeIfAbsent(runtimeIdentity, ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put(objectKey, content);
                    pendingChunks.remove(chunkKey);
                }
                return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(200,
                        "{\"sha256\":\"" + request.path("sha256").asText() + "\",\"versionId\":\"v1\"}");
            }
            if ("get".equals(operation)) {
                byte[] content = storageObjects.getOrDefault(runtimeIdentity, new java.util.concurrent.ConcurrentHashMap<>()).get(objectKey);
                if (content == null) return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(404, "{}");
                String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
                int index = request.path("chunkIndex").asInt();
                int from = index * 512 * 1024;
                byte[] chunk = java.util.Arrays.copyOfRange(content, from, Math.min(content.length, from + 512 * 1024));
                return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(200,
                        "{\"chunkBase64\":\"" + java.util.Base64.getEncoder().encodeToString(chunk) + "\",\"chunkIndex\":" + index
                                + ",\"totalChunks\":" + Math.max(1, (content.length + 512 * 1024 - 1) / (512 * 1024))
                                + ",\"sha256\":\"" + checksum + "\",\"versionId\":\"v1\"}");
            }
            if ("delete".equals(operation)) {
                storageObjects.computeIfAbsent(runtimeIdentity, ignored -> new java.util.concurrent.ConcurrentHashMap<>()).remove(objectKey);
                return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(200,
                        "{\"deleted\":true,\"tombstoneId\":\"" + request.path("tombstoneId").asText() + "\"}");
            }
            return new io.artio.extensions.ExtensionRuntimePort.InvocationResult(400, "{}");
        });
        org.springframework.beans.factory.ObjectProvider<io.artio.extensions.ExtensionRuntimePort> fakeRuntimeProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(fakeRuntimeProvider.getIfAvailable()).thenReturn(fakeRuntime);
        var storageInvoker = new io.artio.api.extensions.CommerceCapabilityInvoker(jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(), encryption, fakeRuntimeProvider);
        var pluginMediaStorage = new io.artio.api.catalog.media.PluginMediaStorage(storageInvoker, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper());
        String testMediaKey = UUID.randomUUID() + ".png";
        byte[] testMediaBytes = "v2 replicated media".getBytes(StandardCharsets.UTF_8);
        pluginMediaStorage.store(testMediaKey, testMediaBytes);
        UUID expectedReplicaId = List.of(instanceId, secondInstanceId).stream().sorted(java.util.Comparator.comparing(UUID::toString))
                .toList().getLast();
        assertThat(jdbc.queryForObject("SELECT replica_instance_ids::text FROM catalog_media_storage_objects WHERE storage_key=?", String.class,
                testMediaKey)).contains(expectedReplicaId.toString());
        assertThat(pluginMediaStorage.read(testMediaKey)).isEqualTo(testMediaBytes);
        String readIdempotencyKey = UUID.nameUUIDFromBytes(("artio-media:get:0:" + testMediaKey).getBytes(StandardCharsets.UTF_8)).toString();
        String encryptedReadResult = jdbc.queryForObject("SELECT result_snapshot::text FROM extension_operation_attempts WHERE idempotency_key=?",
                String.class, readIdempotencyKey);
        assertThat(encryptedReadResult).contains("encryptedReadResult").doesNotContain(java.util.Base64.getEncoder().encodeToString(testMediaBytes));
        pluginMediaStorage.delete(testMediaKey);
        assertThat(jdbc.queryForObject("SELECT object_state FROM catalog_media_storage_objects WHERE storage_key=?", String.class, testMediaKey))
                .isEqualTo("TOMBSTONED");

        // Simulate Core stopping after persisting DELETE_PENDING but before reaching the plugin.
        String pendingDeleteKey = UUID.randomUUID() + ".png";
        pluginMediaStorage.store(pendingDeleteKey, testMediaBytes);
        UUID pendingTombstone = UUID.nameUUIDFromBytes(pendingDeleteKey.getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE catalog_media_storage_objects SET object_state='DELETE_PENDING',tombstone_id=? WHERE storage_key=?",
                pendingTombstone, pendingDeleteKey);
        String deleteIdempotencyKey = UUID.nameUUIDFromBytes(("artio-media:delete:" + pendingDeleteKey).getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.update("DELETE FROM extension_operation_attempts WHERE idempotency_key=?", deleteIdempotencyKey);
        var restartedCoreStorage = new io.artio.api.catalog.media.PluginMediaStorage(storageInvoker, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(restartedCoreStorage.recoverPendingDeletes(25)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT object_state FROM catalog_media_storage_objects WHERE storage_key=?", String.class,
                pendingDeleteKey)).isEqualTo("TOMBSTONED");

        // Exercise the bounded local-source backfill against PostgreSQL and the live Core invocation path.
        String backfillKey = "00000000-0000-4000-8000-000000000001.png";
        UUID backfillProductId = UUID.randomUUID();
        byte[] sourceBytes = "verified local legacy media".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Path legacyMediaDirectory = java.nio.file.Files.createTempDirectory("artio-media-backfill-");
        java.nio.file.Files.write(legacyMediaDirectory.resolve(backfillKey), sourceBytes);
        jdbc.update("INSERT INTO catalog_product(id,name,sku,active,created_at,updated_at) VALUES (?,?,?,TRUE,now(),now())",
                backfillProductId, "Backfill test", "BF-" + backfillProductId.toString().substring(0, 8));
        jdbc.update("INSERT INTO catalog_product_media(id,product_id,storage_key,content_type,width,height,alt_text,position) "
                        + "VALUES (?,?,?,'image/png',1,1,'',0)", UUID.randomUUID(), backfillProductId, backfillKey);
        try {
            var sourceFactory = new io.artio.api.catalog.media.MediaStorageMigrationSourceFactory("local",
                    legacyMediaDirectory.toString(), "", "us-east-1", "", true, "products");
            var backfillService = new io.artio.api.catalog.media.MediaStorageBackfillService(jdbc, restartedCoreStorage, sourceFactory);
            var backfill = backfillService.backfill("00000000-0000-4000-8000-000000000000.png", 1);
            assertThat(backfill.inspected()).isEqualTo(1);
            assertThat(backfill.migrated()).isEqualTo(1);
            assertThat(backfill.failed()).isZero();
            assertThat(restartedCoreStorage.read(backfillKey)).isEqualTo(sourceBytes);
            assertThat(java.nio.file.Files.readAllBytes(legacyMediaDirectory.resolve(backfillKey))).isEqualTo(sourceBytes);
        } finally {
            jdbc.update("DELETE FROM catalog_product WHERE id=?", backfillProductId);
            try (var paths = java.nio.file.Files.walk(legacyMediaDirectory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { java.nio.file.Files.deleteIfExists(path); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            }
        }

        String objectPutKey = UUID.randomUUID().toString();
        assertThatThrownBy(() -> commerceCapabilityInvoker.invoke("storage.object.v1", "put", Map.of(), null, true,
                objectPutKey, Map.of("key", "media-object", "contentBase64", "aGVsbG8=", "sha256", "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")))
                .isInstanceOf(io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException.class);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(plan_snapshot->'targets') FROM extension_operation_attempts WHERE idempotency_key=?",
                Integer.class, objectPutKey)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT plan_snapshot->>'policy' FROM extension_operation_attempts WHERE idempotency_key=?",
                String.class, objectPutKey)).isEqualTo("PRIMARY_REPLICAS");

        String commerceKey = UUID.randomUUID().toString();
        var commercePayload = Map.of("orderId", UUID.randomUUID().toString(), "currency", "CHF", "amount", "12.50");
        assertThatThrownBy(() -> commerceCapabilityInvoker.invoke("payment.provider.v1", "createCheckout",
                Map.of("country", "CH", "currency", "CHF"), null, true, commerceKey, commercePayload))
                .isInstanceOf(io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException.class)
                .satisfies(failure -> assertThat(((io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException) failure)
                        .owner().instanceId()).isEqualTo(instanceId));
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM extension_operation_attempts WHERE idempotency_key=?", String.class, commerceKey))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT plan_snapshot->'targets'->0->>'configurationGeneration' FROM extension_operation_attempts WHERE idempotency_key=?", String.class, commerceKey))
                .isEqualTo("1");
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='DISABLED' WHERE instance_id=?", instanceId);
        assertThatThrownBy(() -> commerceCapabilityInvoker.invoke("payment.provider.v1", "createCheckout",
                Map.of("country", "CH", "currency", "CHF"), null, true, commerceKey, commercePayload))
                .isInstanceOf(io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException.class)
                .satisfies(failure -> assertThat(((io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException) failure)
                        .owner().instanceId()).isEqualTo(instanceId));
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM extension_operation_attempts WHERE idempotency_key=?", Integer.class, commerceKey))
                .isEqualTo(1);
        String webhookKey = UUID.randomUUID().toString();
        assertThatThrownBy(() -> commerceCapabilityInvoker.invoke("payment.provider.v1", "verifyWebhook",
                Map.of("country", "CH"), instanceId, false, webhookKey, Map.of("rawBodyBase64", "e30=")))
                .isInstanceOf(io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException.class)
                .satisfies(failure -> assertThat(((io.artio.api.extensions.CommerceCapabilityInvoker.OutcomeUnknownException) failure)
                        .owner().instanceId()).isEqualTo(instanceId));
        assertThat(jdbc.queryForObject("SELECT instance_id FROM extension_operation_attempts WHERE idempotency_key=?", UUID.class, webhookKey))
                .isEqualTo(instanceId);
        UUID commerceAttemptId = jdbc.queryForObject("SELECT attempt_id FROM extension_operation_attempts WHERE idempotency_key=?", UUID.class, commerceKey);
        var commerceReconciliation = adminExchange("/api/v1/admin/extensions/routing/attempts/{attemptId}/reconcile", HttpMethod.POST,
                Map.of(), Map.class, commerceAttemptId);
        assertThat(commerceReconciliation.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM extension_operation_attempts WHERE attempt_id=?", String.class, commerceAttemptId))
                .isEqualTo("UNKNOWN");
        jdbc.update("UPDATE extension_plugin_instances SET lifecycle_state='ENABLED' WHERE instance_id=?", instanceId);

        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> invocation = Map.of("capabilityId", "payment.provider.v1", "operationName", "createCheckout",
                "scope", Map.of("country", "CH"), "newCommitment", true, "idempotencyKey", idempotencyKey,
                "payload", Map.of("amount", "12.50", "currency", "CHF"), "deadlineMillis", 1000);
        var unavailableCall = adminExchange("/api/v1/admin/extensions/routing/invoke", HttpMethod.POST, invocation, Map.class);
        // The HTTP client retries one 503; the retry sees UNKNOWN and safely receives 202 without another plugin call.
        assertThat(unavailableCall.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unavailableCall.getBody()).containsEntry("state", "UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM extension_operation_attempts WHERE idempotency_key=?", String.class, idempotencyKey))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM extension_operation_attempts WHERE idempotency_key=?", Integer.class, idempotencyKey))
                .isEqualTo(1);
        var repeatedUnknown = adminExchange("/api/v1/admin/extensions/routing/invoke", HttpMethod.POST, invocation, Map.class);
        assertThat(repeatedUnknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM extension_operation_attempts WHERE idempotency_key=?", Integer.class, idempotencyKey))
                .isEqualTo(1);
        String requestCiphertext = jdbc.queryForObject("SELECT request_snapshot FROM extension_operation_attempts WHERE idempotency_key=?", String.class, idempotencyKey);
        assertThat(requestCiphertext).doesNotContain("12.50");
        assertThat(encryption.decrypt(requestCiphertext)).contains("12.50");
        assertThatThrownBy(() -> jdbc.update("UPDATE extension_operation_attempts SET plan_snapshot='{}'::jsonb WHERE idempotency_key=?", idempotencyKey))
                .hasMessageContaining("routing plans are immutable");
        Map<String, Object> mismatchedRetry = new java.util.HashMap<>(invocation);
        mismatchedRetry.put("payload", Map.of("amount", "99.00", "currency", "CHF"));
        assertThat(adminExchange("/api/v1/admin/extensions/routing/invoke", HttpMethod.POST, mismatchedRetry, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        UUID attemptId = jdbc.queryForObject("SELECT attempt_id FROM extension_operation_attempts WHERE idempotency_key=?", UUID.class, idempotencyKey);
        String frozenPlan = jdbc.queryForObject("SELECT plan_snapshot::text FROM extension_operation_attempts WHERE attempt_id=?", String.class, attemptId);
        var reconciliation = adminExchange("/api/v1/admin/extensions/routing/attempts/{attemptId}/reconcile", HttpMethod.POST,
                Map.of(), Map.class, attemptId);
        assertThat(reconciliation.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT attempt_state FROM extension_operation_attempts WHERE attempt_id=?", String.class, attemptId))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT plan_snapshot::text FROM extension_operation_attempts WHERE attempt_id=?", String.class, attemptId))
                .isEqualTo(frozenPlan);

        var summary = adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, Map[].class);
        Map<?, ?> pluginSummary = java.util.Arrays.stream(summary.getBody()).filter(plugin -> pluginId.equals(plugin.get("id")))
                .findFirst().orElseThrow();
        assertThat(pluginSummary.get("compatibilityModes")).isEqualTo("CAPABILITY_V2");
        assertThat(pluginSummary.get("instanceCount")).isEqualTo(2);
        assertThat(pluginSummary.get("bindingCount")).isEqualTo(8);
        assertThat(pluginSummary.get("subscriptionCount")).isEqualTo(2);

        UUID deadDeliveryId = UUID.randomUUID();
        UUID deadEventId = UUID.randomUUID();
        Long pluginReleaseId = v2ReleaseId;
        jdbc.update("INSERT INTO event_inbox(consumer_name,event_id,event_type,schema_version,aggregate_id) "
                + "VALUES ('order-notification-v1',?,'ORDER_PAID',1,?)", deadEventId, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO extension_event_deliveries(delivery_id,inbox_consumer_name,inbox_event_id,subscription_id,"
                        + "instance_id,plugin_id,runtime_identity,release_id,configuration_generation,runtime_credential_generation,"
                        + "event_type,schema_version,payload_snapshot,state,attempt_count,last_error_code) "
                        + "VALUES (?,'order-notification-v1',?,?,?,?,?,?,0,1,'order.paid',1,'{\"eventType\":\"order.paid\"}'::jsonb,"
                        + "'DEAD_LETTER',12,'ExtensionUnavailableException')",
                deadDeliveryId, deadEventId, subscriptionId, instanceId, pluginId, pluginId, pluginReleaseId);
        var deadDeliveries = adminExchange("/api/v1/admin/extensions/events/dead?page=0&size=10", HttpMethod.GET,
                null, Map[].class);
        assertThat(java.util.Arrays.stream(deadDeliveries.getBody()).map(row -> row.get("deliveryId")))
                .contains(deadDeliveryId.toString());
        var replay = adminExchange("/api/v1/admin/extensions/events/{deliveryId}/replay", HttpMethod.POST,
                Map.of(), Map.class, deadDeliveryId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT state FROM extension_event_deliveries WHERE delivery_id=?", String.class, deadDeliveryId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM extension_audit_events WHERE plugin_id=? AND action='REPLAY_EVENT_DELIVERY' "
                + "AND details->>'deliveryId'=?", Integer.class, pluginId, deadDeliveryId.toString())).isEqualTo(1);

        var instanceHealth = adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}/health", HttpMethod.GET,
                null, Map.class, pluginId, instanceId);
        var obligations = adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}/obligations", HttpMethod.GET,
                null, Map.class, pluginId, instanceId);
        assertThat(instanceHealth.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(instanceHealth.getBody()).containsEntry("healthy", false);
        assertThat(((Number) obligations.getBody().get("operations")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) obligations.getBody().get("deliveries")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) obligations.getBody().get("total")).longValue()).isGreaterThanOrEqualTo(2);
        jdbc.update("UPDATE extension_event_subscriptions SET subscription_state='ACTIVE' WHERE subscription_id=?", subscriptionId);
        var drained = adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}/drain", HttpMethod.POST,
                Map.of(), Map.class, pluginId, instanceId);
        assertThat(drained.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE instance_id=?", String.class,
                instanceId)).isEqualTo("DRAINING");
        assertThat(jdbc.queryForObject("SELECT eligibility_state FROM extension_capability_bindings WHERE binding_id=?", String.class,
                UUID.fromString(binding.getBody().get("bindingId").toString()))).isEqualTo("DRAINING");
        assertThat(jdbc.queryForObject("SELECT subscription_state FROM extension_event_subscriptions WHERE subscription_id=?", String.class,
                subscriptionId)).isEqualTo("DRAINING");

        var obligationInstance = adminExchange("/api/v1/admin/extensions/{id}/instances", HttpMethod.POST,
                Map.of("instanceKey", "uninstall-obligation", "configuration", Map.of("account", "CH", "apiKey", "obligation-key")),
                Map.class, pluginId);
        UUID obligationInstanceId = UUID.fromString(obligationInstance.getBody().get("instanceId").toString());
        UUID obligationOrderId = UUID.randomUUID();
        UUID obligationPaymentId = UUID.randomUUID();
        UUID obligationRefundId = UUID.randomUUID();
        UUID obligationShipmentId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,"
                            + "shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                            + "VALUES (?,?,'PENDING',10,0,0,10,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",
                    obligationOrderId, adminUserId, "uninstall-obligation-" + obligationOrderId);
            jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,provider_instance_id,"
                            + "provider_release_id,provider_configuration_generation,created_at,updated_at) "
                            + "VALUES (?,?,?,10,'CHF','EXTENSION','PENDING',?,?,?,0,now(),now())",
                    obligationPaymentId, obligationOrderId, adminUserId, "uninstall-obligation-" + obligationPaymentId,
                    obligationInstanceId, pluginReleaseId);
            assertThat(adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}", HttpMethod.DELETE,
                    null, Map.class, pluginId, obligationInstanceId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            jdbc.update("UPDATE payments SET status='CAPTURED' WHERE id=?", obligationPaymentId);
            jdbc.update("INSERT INTO payment_refunds(id,payment_id,idempotency_key,amount,currency,status,created_at,updated_at) "
                            + "VALUES (?,?,?,10,'CHF','PROCESSING',now(),now())",
                    obligationRefundId, obligationPaymentId, "uninstall-refund-" + obligationRefundId);
            assertThat(adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}", HttpMethod.DELETE,
                    null, Map.class, pluginId, obligationInstanceId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

            jdbc.update("UPDATE payment_refunds SET status='FAILED' WHERE id=?", obligationRefundId);
            jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", obligationOrderId);
            jdbc.update("INSERT INTO order_shipments(id,order_id,tracking_number,provider_instance_id,provider_release_id,"
                            + "provider_configuration_generation) VALUES (?,?,?, ?,?,0)",
                    obligationShipmentId, obligationOrderId, "tracking-" + obligationShipmentId, obligationInstanceId, pluginReleaseId);
            assertThat(adminExchange("/api/v1/admin/extensions/{id}/instances/{instanceId}", HttpMethod.DELETE,
                    null, Map.class, pluginId, obligationInstanceId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugin_instances WHERE instance_id=?", String.class,
                    obligationInstanceId)).isEqualTo("CONFIGURED");
        } finally {
            jdbc.update("DELETE FROM order_shipments WHERE id=?", obligationShipmentId);
            jdbc.update("DELETE FROM payment_refunds WHERE id=?", obligationRefundId);
            jdbc.update("DELETE FROM payments WHERE id=?", obligationPaymentId);
            jdbc.update("DELETE FROM orders WHERE id=?", obligationOrderId);
        }
    }

    @Test
    void installationSettingsAreRestrictedToSuperadminAndAudited() {
        var denied = adminExchange("/api/v1/admin/system/settings", HttpMethod.GET, null, ApiError.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().code()).isEqualTo("FORBIDDEN");

        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "owner-" + ownerId + "@example.test";
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'SUPERADMIN',now())",
                ownerId, ownerEmail, new BCryptPasswordEncoder().encode("owner-password-123"), "System", "Owner");
        String ownerSecret=io.artio.api.security.Totp.newSecret();
        jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret,verified,verified_at) VALUES (?,?,TRUE,now())",ownerId,encryption.encrypt(ownerSecret));
        LoginResponse ownerLogin = http.postForObject("/api/v1/auth/login", Map.of("email", ownerEmail, "password", "owner-password-123","mfaCode",io.artio.api.TestTotp.code(ownerSecret,java.time.Instant.now())), LoginResponse.class);
        assertThat(ownerLogin).isNotNull();
        adminToken = ownerLogin.accessToken();

        var defaults = adminExchange("/api/v1/admin/system/settings", HttpMethod.GET, null,
                io.artio.api.enterprise.SystemSettingsController.SystemSettingsView.class);
        assertThat(defaults.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(defaults.getBody()).isNotNull();
        assertThat(defaults.getBody().distributionId()).isEqualTo("switzerland-v1");

        var saved = adminExchange("/api/v1/admin/system/settings", HttpMethod.PUT,
                new io.artio.api.enterprise.SystemSettingsController.SystemSettingsUpdate("Studio Zürich", "CH", "CHF", "de-CH",
                        List.of("de", "fr", "it", "en"), "Europe/Zurich", "https://shop.example.test"),
                io.artio.api.enterprise.SystemSettingsController.SystemSettingsView.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).isNotNull();
        assertThat(saved.getBody().storeName()).isEqualTo("Studio Zürich");
        assertThat(saved.getBody().availableLanguages()).containsExactly("de", "fr", "it", "en");

        var publicConfig = http.getForEntity("/api/v1/public/config", JsonNode.class);
        assertThat(publicConfig.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicConfig.getBody().path("countryCode").asText()).isEqualTo("CH");
        assertThat(publicConfig.getBody().path("currencyCode").asText()).isEqualTo("CHF");
        assertThat(publicConfig.getBody().path("locale").asText()).isEqualTo("de-CH");

        var invalidUrl = adminExchange("/api/v1/admin/system/settings", HttpMethod.PUT,
                new io.artio.api.enterprise.SystemSettingsController.SystemSettingsUpdate("Studio Zürich", "CH", "CHF", "de-CH",
                        List.of("de"), "Europe/Zurich", "http://shop.example.test"), ApiError.class);
        assertThat(invalidUrl.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void encryptionRotationRequiresSuperadminAndRewrapsMfaAndPluginSecrets() throws Exception {
        String path = "/api/v1/admin/system/security/encryption/rotate";
        assertThat(adminExchange(path, HttpMethod.POST, null, ApiError.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE users SET role='SUPERADMIN' WHERE id=?", adminUserId);
        var ownerLogin = http.postForObject("/api/v1/auth/login",
                Map.of("email", "admin-" + adminUserId + "@example.test", "password", "admin-password-123","mfaCode",io.artio.api.TestTotp.code(adminTotpSecret,java.time.Instant.now())), LoginResponse.class);
        assertThat(ownerLogin).isNotNull();
        adminToken = ownerLogin.accessToken();

        var previousEncryption = new io.artio.api.security.EncryptionService(PREVIOUS_TEST_ENCRYPTION_KEY);
        String oldMfaCiphertext = previousEncryption.encrypt("mfa-secret-material");
        String oldPluginCiphertext = previousEncryption.encrypt("provider-secret-material");
        String oldDeadLetterCiphertext = previousEncryption.encrypt("private-dead-letter-material");
        UUID deadLetterId = UUID.randomUUID();
        UUID tombstoneId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kafka_consumer_dead_letters(id,source_topic,source_partition,source_offset,encrypted_payload,error_class)
                VALUES (?,'rotation-test',0,0,?,'TestFailure')
                """, deadLetterId, oldDeadLetterCiphertext);
        jdbc.update("""
                INSERT INTO kafka_consumer_dead_letters(id,source_topic,source_partition,source_offset,payload_is_null,error_class)
                VALUES (?,'rotation-test',0,1,true,'TestFailure')
                """, tombstoneId);
        jdbc.update("UPDATE mfa_methods SET encrypted_secret=? WHERE user_id=?", oldMfaCiphertext, adminUserId);
        jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state,encrypted_secrets) "
                        + "VALUES ('test.key-rotation','{}'::jsonb,'ghcr.io/artio/test@sha256:'||repeat('a',64),'VALIDATED',?::jsonb)",
                mapper.writeValueAsString(Map.of("apiKey", oldPluginCiphertext)));

        var rotated = adminExchange(path, HttpMethod.POST, null,
                io.artio.api.security.EncryptionKeyRotationController.RotationResult.class);

        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).isNotNull();
        assertThat(rotated.getBody().activeKeyId()).isEqualTo(io.artio.api.security.EncryptionService.keyId(ACTIVE_TEST_ENCRYPTION_KEY));
        assertThat(rotated.getBody().mfaSecretsRewrapped()).isEqualTo(1);
        assertThat(rotated.getBody().extensionSecretsRewrapped()).isEqualTo(1);
        assertThat(rotated.getBody().deadLetterPayloadsRewrapped()).isEqualTo(1);
        String newMfaCiphertext = jdbc.queryForObject("SELECT encrypted_secret FROM mfa_methods WHERE user_id=?", String.class, adminUserId);
        String newPluginCiphertext = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugins WHERE plugin_id='test.key-rotation'", String.class);
        assertThat(encryption.isEncryptedWithActiveKey(newMfaCiphertext)).isTrue();
        assertThat(encryption.isEncryptedWithActiveKey(newPluginCiphertext)).isTrue();
        assertThat(encryption.decrypt(newMfaCiphertext)).isEqualTo("mfa-secret-material");
        assertThat(encryption.decrypt(newPluginCiphertext)).isEqualTo("provider-secret-material");
        String newDeadLetterCiphertext = jdbc.queryForObject(
                "SELECT encrypted_payload FROM kafka_consumer_dead_letters WHERE id=?", String.class, deadLetterId);
        var activeOnlyEncryption = new io.artio.api.security.EncryptionService(ACTIVE_TEST_ENCRYPTION_KEY);
        assertThat(activeOnlyEncryption.decrypt(newDeadLetterCiphertext)).isEqualTo("private-dead-letter-material");
        assertThat(jdbc.queryForObject("SELECT encrypted_payload FROM kafka_consumer_dead_letters WHERE id=?", String.class, tombstoneId)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE path=? AND action='UPDATE'", Integer.class, path)).isEqualTo(1);

        var repeated = adminExchange(path, HttpMethod.POST, null,
                io.artio.api.security.EncryptionKeyRotationController.RotationResult.class);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().deadLetterPayloadsRewrapped()).isZero();
        assertThat(repeated.getBody().mfaSecretsRewrapped()).isZero();
        assertThat(repeated.getBody().extensionSecretsRewrapped()).isZero();

        // A corrupt DLQ payload must roll back the preceding MFA/plugin updates and the audit event.
        jdbc.update("UPDATE mfa_methods SET encrypted_secret=? WHERE user_id=?", oldMfaCiphertext, adminUserId);
        jdbc.update("UPDATE extension_plugins SET encrypted_secrets=?::jsonb WHERE plugin_id='test.key-rotation'",
                mapper.writeValueAsString(Map.of("apiKey", oldPluginCiphertext)));
        jdbc.update("UPDATE kafka_consumer_dead_letters SET encrypted_payload='invalid-ciphertext' WHERE id=?", deadLetterId);
        try {
            var failed = adminExchange(path, HttpMethod.POST, null, String.class);
            assertThat(failed.getStatusCode().isError()).isTrue();
            assertThat(failed.getBody()).doesNotContain("private-dead-letter-material", "provider-secret-material", "mfa-secret-material");
            assertThat(jdbc.queryForObject("SELECT encrypted_secret FROM mfa_methods WHERE user_id=?", String.class, adminUserId)).isEqualTo(oldMfaCiphertext);
            assertThat(jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugins WHERE plugin_id='test.key-rotation'", String.class)).isEqualTo(oldPluginCiphertext);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE path=? AND action='UPDATE'", Integer.class, path)).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM kafka_consumer_dead_letters WHERE source_topic='rotation-test'");
            jdbc.update("DELETE FROM extension_plugin_instances WHERE plugin_id='test.key-rotation'");
            jdbc.update("DELETE FROM extension_plugins WHERE plugin_id='test.key-rotation'");
        }
    }

    @Test
    void extensionLifecycleEnforcesDependencyStateAndVersionBeforeRuntimeCalls() {
        var base = new io.artio.extensions.PluginManifest("1", "common.transport", "Common transport", "Artio",
                "1.5.0", "Transport contract", "0.1.0", "1.0.0",
                "ghcr.io/artio/common-transport@sha256:" + "c".repeat(64), java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), java.util.List.of("shipping.provider.v1"), "/health", "/diagnostics");
        var dependent = new io.artio.extensions.PluginManifest("1", "shipping.example", "Example shipping", "Artio",
                "1.0.0", "Shipping extension", "0.1.0", "1.0.0",
                "ghcr.io/artio/example-shipping@sha256:" + "d".repeat(64),
                java.util.List.of(new io.artio.extensions.PluginManifest.Dependency("common.transport", "1.0.0", "2.0.0")),
                java.util.List.of("shipping.quote"), java.util.Map.of(), java.util.List.of("shipping.provider.v1"),
                "/health", "/diagnostics");
        adminExchange("/api/v1/admin/extensions/register", HttpMethod.POST, base,
                io.artio.api.extensions.ExtensionManagementController.PluginView.class);
        adminExchange("/api/v1/admin/extensions/register", HttpMethod.POST, dependent,
                io.artio.api.extensions.ExtensionManagementController.PluginView.class);
        var configuration = adminExchange("/api/v1/admin/extensions/{id}/configuration", HttpMethod.PUT, Map.of(),
                io.artio.api.extensions.ExtensionManagementController.PluginView.class, dependent.id());
        assertThat(configuration.getStatusCode()).as("configuration response: %s", configuration.getBody())
                .isEqualTo(HttpStatus.OK);

        var install = adminExchange("/api/v1/admin/extensions/{id}/install", HttpMethod.POST, null, Map.class, dependent.id());
        assertThat(install.getStatusCode()).as("install response: %s", install.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(install.getBody()).containsEntry("valid", false);
        assertThat(String.valueOf(install.getBody().get("errors"))).contains("dependency is not installed: common.transport");

        jdbc.update("UPDATE extension_plugins SET lifecycle_state='INSTALLED' WHERE plugin_id IN (?,?)", base.id(), dependent.id());
        var unavailableRotation = adminExchange("/api/v1/admin/extensions/{id}/credentials/rotate", HttpMethod.POST, null, ApiError.class, base.id());
        assertThat(unavailableRotation.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT credential_generation FROM extension_plugins WHERE plugin_id=?", Integer.class, base.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM extension_plugins WHERE plugin_id=?", String.class, base.id())).isEqualTo("INSTALLED");
        var update = new io.artio.extensions.PluginManifest("1", dependent.id(), dependent.name(), dependent.vendor(), "1.1.0",
                dependent.description(), dependent.minimumCoreVersion(), dependent.maximumCoreVersion(),
                "ghcr.io/artio/example-shipping@sha256:" + "e".repeat(64), dependent.dependencies(), dependent.permissions(),
                dependent.configuration(), dependent.extensionPoints(), dependent.healthPath(), dependent.diagnosticsPath());
        var unavailableUpdate = adminExchange("/api/v1/admin/extensions/{id}/update", HttpMethod.POST, update, ApiError.class, dependent.id());
        assertThat(unavailableUpdate.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT manifest->>'version' FROM extension_plugins WHERE plugin_id=?", String.class, dependent.id())).isEqualTo("1.0.0");
        var noRollback = adminExchange("/api/v1/admin/extensions/{id}/rollback", HttpMethod.POST, null, Map.class, dependent.id());
        assertThat(noRollback.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var enable = adminExchange("/api/v1/admin/extensions/{id}/enable", HttpMethod.POST, null, Map.class, dependent.id());
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(enable.getBody()).containsEntry("message", "Plugin dependencies are not ready");

        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED' WHERE plugin_id=?", base.id());
        var runtimeDisabled = adminExchange("/api/v1/admin/extensions/{id}/enable", HttpMethod.POST, null, ApiError.class, dependent.id());
        assertThat(runtimeDisabled.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        jdbc.update("UPDATE extension_plugins SET lifecycle_state='ENABLED' WHERE plugin_id=?", dependent.id());
        var disable = adminExchange("/api/v1/admin/extensions/{id}/disable", HttpMethod.POST, null, Map.class, base.id());
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(disable.getBody()).containsEntry("dependents", List.of(dependent.id()));
    }

    @Test
    void priceTaxInclusionModeIsPersistedAndExtractedFromCustomerPrice() {
        UUID sellableId = UUID.randomUUID();
        var price = new io.artio.api.pricing.PricingApplicationService.PriceRequest(sellableId, "CH", "WEB",
                new BigDecimal("108.10"), "CHF", null, null, true);
        var created = adminExchange("/api/v1/prices", HttpMethod.POST, price,
                io.artio.api.pricing.PricingApplicationService.PriceView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().pricesIncludeTax()).isTrue();

        var quote = adminExchange("/api/v1/pricing/quote", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.QuoteRequest(sellableId, "CH", "WEB", "CH", BigDecimal.ONE, null),
                io.artio.pricing.PricingQuote.class);
        assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quote.getBody()).isNotNull();
        assertThat(quote.getBody().pricesIncludeTax()).isTrue();
        assertThat(quote.getBody().taxableAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(quote.getBody().tax().amount()).isEqualByComparingTo("8.10");
        assertThat(quote.getBody().total().amount()).isEqualByComparingTo("108.10");
    }

    @Test
    void expiryJobReleasesReservedStockAndDoesNotConsumeOnHand() {
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("EXP-01", "Expiry warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getBody()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("5")),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);

        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouseId, sellableId, new BigDecimal("2")));
        assertThat(reservation.expiresAt()).isAfter(java.time.Instant.now());
        jdbc.update("UPDATE inventory_reservation SET expires_at=now()-interval '1 second' WHERE id=?", reservation.id());

        assertThat(inventory.expireDueReservations(java.time.Instant.now(), 100)).isEqualTo(1);
        assertThat(inventory.reservationsByOrder(orderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
        assertThat(inventory.stockBySellable(sellableId)).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.onHand()).isEqualByComparingTo("5");
                    assertThat(stock.reserved()).isEqualByComparingTo("0");
                    assertThat(stock.available()).isEqualByComparingTo("5");
                });
    }

    @Test
    void lowStockPoliciesTrackAvailableStockAndCanBeDisabled() {
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("LOW-01", "Low stock warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("5")),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/low-stock-policy", HttpMethod.PUT,
                new io.artio.api.inventory.InventoryApplicationService.LowStockPolicyRequest(new BigDecimal("4"), true),
                io.artio.api.inventory.InventoryApplicationService.LowStockPolicyView.class, warehouseId, sellableId);
        assertThat(inventory.lowStock()).noneMatch(alert -> alert.sellableId().equals(sellableId));
        inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(UUID.randomUUID(), warehouseId, sellableId, new BigDecimal("2")));
        assertThat(inventory.lowStock()).anySatisfy(alert -> {
            assertThat(alert.sellableId()).isEqualTo(sellableId);
            assertThat(alert.available()).isEqualByComparingTo("3");
            assertThat(alert.threshold()).isEqualByComparingTo("4");
        });
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/low-stock-policy", HttpMethod.PUT,
                new io.artio.api.inventory.InventoryApplicationService.LowStockPolicyRequest(new BigDecimal("4"), false),
                io.artio.api.inventory.InventoryApplicationService.LowStockPolicyView.class, warehouseId, sellableId);
        assertThat(inventory.lowStock()).noneMatch(alert -> alert.sellableId().equals(sellableId));
    }

    @Test
    void productSeoMetadataIsAdminManagedAndReturnedByPublicCatalogReads() {
        var product=adminExchange("/api/v1/products",HttpMethod.POST,
                new ProductRequest("SEO test lamp","Brass lamp","SEO-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT)),ProductResponse.class);
        String slug="brass-lamp-"+UUID.randomUUID().toString().substring(0,8);
        var updated=adminExchange("/api/v1/products/{id}/seo",HttpMethod.PUT,
                new ProductApplicationService.SeoMetadataRequest(slug,"Brass lamp | Artio","A durable brass desk lamp."),ProductResponse.class,product.getBody().id());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().seoSlug()).isEqualTo(slug);
        assertThat(updated.getBody().seoTitle()).isEqualTo("Brass lamp | Artio");
        var publicRead=http.getForEntity("/api/v1/products/{id}",ProductResponse.class,product.getBody().id());
        assertThat(publicRead.getBody()).isNotNull();
        assertThat(publicRead.getBody().seoDescription()).isEqualTo("A durable brass desk lamp.");
    }

    @Test
    void backofficeCatalogQueryFiltersAndPaginatesProducts() {
        String uniqueSku="QUERY-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT);
        var product=adminExchange("/api/v1/products",HttpMethod.POST,
                new ProductRequest("Query lamp","Query description",uniqueSku),ProductResponse.class);
        var page=adminExchange("/api/v1/admin/catalog/products?q="+uniqueSku+"&state=ACTIVE&page=0&size=1&sort=name&direction=asc",
                HttpMethod.GET,null,AdminCatalogProductQueryController.ProductPage.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).isNotNull();
        assertThat(page.getBody().total()).isEqualTo(1);
        assertThat(page.getBody().items()).anySatisfy(item->assertThat(item.id()).isEqualTo(product.getBody().id()));
        var forbidden=http.getForEntity("/api/v1/admin/catalog/products",String.class);
        assertThat(forbidden.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN);
    }

    @Test
    void productArchiveRetainsRecordAndRestoresAsDraft() {
        var product=adminExchange("/api/v1/products",HttpMethod.POST,
                new ProductRequest("Archive lamp","Kept for order history","ARCH-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT)),ProductResponse.class);
        var variant=adminExchange("/api/v1/products/{id}/variants",HttpMethod.POST,
                new VariantRequest("Desk size","ARCH-V-"+UUID.randomUUID().toString().substring(0,8).toUpperCase(java.util.Locale.ROOT)),VariantResponse.class,product.getBody().id());
        var archived=adminExchange("/api/v1/products/{id}/archive",HttpMethod.PUT,null,ProductResponse.class,product.getBody().id());
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().archived()).isTrue();
        assertThat(archived.getBody().active()).isFalse();
        String retainedSku = jdbc.queryForObject("SELECT sku FROM catalog_product WHERE id=?", String.class, product.getBody().id());
        assertThat(retainedSku).isEqualTo(product.getBody().sku());
        var blockedVariantActivation=adminExchange("/api/v1/variants/{id}/active",HttpMethod.PUT,new SetActiveRequest(true),ApiError.class,variant.getBody().id());
        assertThat(blockedVariantActivation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var restored=adminExchange("/api/v1/products/{id}/restore",HttpMethod.PUT,null,ProductResponse.class,product.getBody().id());
        assertThat(restored.getBody()).isNotNull();
        assertThat(restored.getBody().archived()).isFalse();
        assertThat(restored.getBody().active()).isFalse();
    }

    @Test
    void variantCombinationsAreTypedUniqueEditableAndPersisted() {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Variant shirt", "Combination test", "VAR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        adminExchange("/api/v1/attribute-definitions", HttpMethod.POST,
                new MetadataApplicationService.AttributeRequest("color", "Color", io.artio.catalog.AttributeDefinition.Type.TEXT, false),
                MetadataApplicationService.AttributeView.class);
        adminExchange("/api/v1/attribute-definitions", HttpMethod.POST,
                new MetadataApplicationService.AttributeRequest("size", "Size", io.artio.catalog.AttributeDefinition.Type.TEXT, false),
                MetadataApplicationService.AttributeView.class);

        var created = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Navy small", "VAR-NAVY-S", Map.of("color", "navy", "size", "S")),
                VariantResponse.class, product.getBody().id());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().attributes()).containsExactlyInAnyOrderEntriesOf(Map.of("color", "navy", "size", "S"));
        assertThatThrownBy(() -> variantApplicationService.create(product.getBody().id(),
                new VariantRequest("Duplicate", "VAR-NAVY-S2", Map.of("size", "S", "color", "navy"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same option combination");

        var anonymousUpdate = http.exchange("/api/v1/variants/{id}", HttpMethod.PUT,
                new HttpEntity<>(new VariantRequest("Navy small", "VAR-NAVY-S", Map.of("color", "navy", "size", "S"))),
                ApiError.class, created.getBody().id());
        assertThat(anonymousUpdate.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var updated = adminExchange("/api/v1/variants/{id}", HttpMethod.PUT,
                new VariantRequest("Navy medium", "VAR-NAVY-M", Map.of("color", "navy", "size", "M")),
                VariantResponse.class, created.getBody().id());
        assertThat(updated.getBody().name()).isEqualTo("Navy medium");
        assertThat(updated.getBody().attributes()).containsEntry("size", "M");
        var persisted = http.getForEntity("/api/v1/variants/{id}", VariantResponse.class, created.getBody().id());
        assertThat(persisted.getBody().attributes()).containsEntry("color", "navy").containsEntry("size", "M");
    }

    @Test
    void scheduledProductPublicationCanBeCancelledOrPublishedWhenDue() {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Scheduled item", "Publication test", "SCH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        adminExchange("/api/v1/products/{id}/active", HttpMethod.PUT,
                new SetActiveRequest(false), ProductResponse.class, product.getBody().id());
        var publishAt = java.time.Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var scheduled = adminExchange("/api/v1/products/{id}/publication-schedule", HttpMethod.PUT,
                new ProductController.PublicationScheduleRequest(publishAt), ProductResponse.class, product.getBody().id());
        assertThat(scheduled.getBody().active()).isFalse();
        assertThat(scheduled.getBody().scheduledPublishAt()).isEqualTo(publishAt);

        var anonymousCancel = http.exchange("/api/v1/products/{id}/publication-schedule", HttpMethod.DELETE,
                HttpEntity.EMPTY, ApiError.class, product.getBody().id());
        assertThat(anonymousCancel.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        int processed = productApplicationService.publishDueProducts(publishAt, 100);
        assertThat(processed).isGreaterThanOrEqualTo(1);
        var published = http.getForEntity("/api/v1/products/{id}", ProductResponse.class, product.getBody().id());
        assertThat(published.getBody().active()).isTrue();
        assertThat(published.getBody().scheduledPublishAt()).isNull();
    }

    @Test
    void csvImportReportsRowErrorsUpsertsBySkuAndExportsCsv() {
        String csv="sku,name,description,active\r\nCSV-IMP-001,\"Lamp, small\",First,true\r\nCSV-IMP-001,\"Lamp, updated\",Second,false\r\nlower-sku,Invalid,Rejected,true\r\n";
        var parts=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        parts.add("file",new org.springframework.core.io.ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){return "products.csv";}
        });
        var headers=new HttpHeaders();headers.setBearerAuth(adminToken);headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var imported=http.exchange("/api/v1/admin/catalog/products/import",HttpMethod.POST,new HttpEntity<>(parts,headers),ProductCsvImportService.ImportReport.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody()).isNotNull();
        assertThat(imported.getBody().rows()).isEqualTo(3);
        assertThat(imported.getBody().created()).isEqualTo(1);
        assertThat(imported.getBody().updated()).isEqualTo(1);
        assertThat(imported.getBody().failed()).isEqualTo(1);
        var exported=adminExchange("/api/v1/admin/catalog/products/export",HttpMethod.GET,null,String.class);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exported.getBody()).contains("sku,name,description,active","CSV-IMP-001");
    }

    @Test
    void productMediaRequiresStaffAndSupportsSafeUploadReplacementOrderingAndDeletion() throws Exception {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Media lamp", "Image workflow", "MEDIA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        byte[] firstImage = testPng(3, 2);
        var anonymousParts = imagePart(firstImage);
        var anonymousHeaders = new HttpHeaders();
        anonymousHeaders.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var anonymous = http.postForEntity("/api/v1/products/{id}/media", new HttpEntity<>(anonymousParts, anonymousHeaders),
                String.class, product.getBody().id());
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var first = uploadProductImage(product.getBody().id(), firstImage, "Front view");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().width()).isEqualTo(3);
        assertThat(first.getBody().altText()).isEqualTo("Front view");
        var downloaded = http.getForEntity(first.getBody().contentUrl(), byte[].class);
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.IMAGE_PNG);

        var second = uploadProductImage(product.getBody().id(), testPng(4, 1), "Side view");
        assertThat(second.getStatusCode()).as("Second media upload status").isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isNotNull();
        var order = adminExchange("/api/v1/products/{id}/media/order", HttpMethod.PUT,
                new ProductMediaController.OrderRequest(List.of(second.getBody().id(), first.getBody().id())),
                ProductMediaController.MediaResponse[].class, product.getBody().id());
        assertThat(order.getBody()).extracting(ProductMediaController.MediaResponse::id)
                .containsExactly(second.getBody().id(), first.getBody().id());

        var replacementParts = imagePart(testPng(8, 5));
        var replacementHeaders = new HttpHeaders();
        replacementHeaders.setBearerAuth(adminToken);
        replacementHeaders.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var replaced = http.exchange("/api/v1/products/{id}/media/{mediaId}?altText={altText}", HttpMethod.PUT,
                new HttpEntity<>(replacementParts, replacementHeaders), ProductMediaController.MediaResponse.class,
                product.getBody().id(), first.getBody().id(), "Front detail");
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replaced.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(replaced.getBody().width()).isEqualTo(8);
        assertThat(replaced.getBody().height()).isEqualTo(5);
        assertThat(replaced.getBody().altText()).isEqualTo("Front detail");

        var deleted = adminExchange("/api/v1/products/{id}/media/{mediaId}", HttpMethod.DELETE, null,
                Void.class, product.getBody().id(), second.getBody().id());
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var remaining = http.getForEntity("/api/v1/products/{id}/media", ProductMediaController.MediaResponse[].class,
                product.getBody().id());
        assertThat(remaining.getBody()).hasSize(1);
        assertThat(remaining.getBody()[0].id()).isEqualTo(first.getBody().id());
    }

    private ResponseEntity<ProductMediaController.MediaResponse> uploadProductImage(UUID productId, byte[] bytes, String altText) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var raw = http.exchange("/api/v1/products/{id}/media?altText={altText}", HttpMethod.POST,
                new HttpEntity<>(imagePart(bytes), headers), String.class, productId, altText);
        assertThat(raw.getStatusCode()).as("Media upload response: %s", raw.getBody()).isEqualTo(HttpStatus.CREATED);
        var response = mapper.readValue(raw.getBody(), ProductMediaController.MediaResponse.class);
        return new ResponseEntity<>(response, raw.getHeaders(), raw.getStatusCode());
    }

    private static org.springframework.util.LinkedMultiValueMap<String, Object> imagePart(byte[] bytes) {
        var parts = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        parts.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return "image.png"; }
        });
        return parts;
    }

    private static byte[] testPng(int width, int height) throws IOException {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (var bytes = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    void rejectedCheckoutCompensationCancelsOrderAndReleasesStockAtomically() {
        UUID orderId = UUID.randomUUID();
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "COMP-" + orderId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Compensation warehouse", "CH-ZH"));
        UUID warehouseId = warehouse.id();
        inventory.receive(warehouseId, sellableId, BigDecimal.ONE);
        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouseId, sellableId, BigDecimal.ONE));
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',6,0,0,6,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "compensation-" + orderId);
        UUID giftCardId = UUID.randomUUID();
        jdbc.update("INSERT INTO gift_cards(id,code_hash,initial_balance,balance,currency,active) VALUES (?,?,5,0,'CHF',false)",
                giftCardId, "a".repeat(64));
        UUID giftCardPaymentId = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,gift_card_id,created_at,updated_at) "
                        + "VALUES (?,?,?,5,'CHF','GIFT_CARD','CAPTURED',?,?,now(),now())",
                giftCardPaymentId, orderId, adminUserId, "giftcard-compensation-" + orderId, giftCardId);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'CHF','EXTENSION','PENDING',?,now(),now())",
                UUID.randomUUID(), orderId, adminUserId, "cs_compensation_" + orderId);

        checkoutFailureCompensation.cancelRejectedCheckout(orderId);

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=? AND method='EXTENSION'", String.class, orderId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT balance FROM gift_cards WHERE id=?", BigDecimal.class, giftCardId)).isEqualByComparingTo("5.00");
        assertThat(jdbc.queryForObject("SELECT active FROM gift_cards WHERE id=?", Boolean.class, giftCardId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, giftCardPaymentId)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gift_card_transactions WHERE payment_refund_id IN " +
                "(SELECT id FROM payment_refunds WHERE payment_id=?) AND kind='REFUND'", Integer.class, giftCardPaymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_refunds WHERE payment_id=?", String.class, giftCardPaymentId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_timeline_events WHERE order_id=? AND event_type='ORDER_CANCELLED' AND customer_visible", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE aggregate_id=? AND event_type='ORDER_CANCELLED'", Integer.class, orderId.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND dedupe_key=?", Integer.class,
                adminUserId, "order-cancelled:" + orderId)).isEqualTo(1);
        assertThat(inventory.reservationsByOrder(orderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
        assertThat(inventory.stockBySellable(sellableId)).singleElement()
                .satisfies(stock -> {
                    assertThat(stock.onHand()).isEqualByComparingTo("1");
                    assertThat(stock.reserved()).isEqualByComparingTo("0");
                });
    }

    @Test
    void definitivePaymentProviderRejectionReturns422AndCancelsThePendingCheckout() {
        UUID orderId = UUID.randomUUID();
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "ROUTE-" + orderId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Checkout route", "CH-ZH"));
        inventory.receive(warehouse.id(), sellableId, BigDecimal.ONE);
        inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouse.id(), sellableId, BigDecimal.ONE));
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',1,0,0,1,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "route-rejection-" + orderId);
        PROVIDER_CHECKOUT_FAILURE.set(new PaymentProvider.CheckoutRejectedException("PROVIDER_REJECTED_422"));
        var discovered = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        List<Map> options = (List<Map>) discovered.getBody().get("methods");
        String cardReference = (String) findPaymentOption(options, "Card").get("reference");
        var headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);

        var response = http.exchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                new HttpEntity<>(Map.of("methodReference", cardReference), headers), ApiError.class, orderId);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(inventory.reservationsByOrder(orderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
    }

    @Test
    void paymentMethodReferenceIsOpaqueShortLivedAndPersistedWithItsCheckout() {
        UUID orderId = UUID.randomUUID();
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "PAY-" + orderId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Payment reference warehouse", "CH-ZH"));
        inventory.receive(warehouse.id(), sellableId, BigDecimal.ONE);
        inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouse.id(), sellableId, BigDecimal.ONE));
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',12.5,0,0,12.5,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "payment-reference-" + orderId);
        var discovery = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        assertThat(discovery.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> options = (List<Map>) discovery.getBody().get("methods");
        assertPaymentOptionShape(options);
        Map<?, ?> card = findPaymentOption(options, "Card");
        String staleCardReference = (String) card.get("reference");
        assertThat(jdbc.queryForObject("SELECT method_id FROM payment_method_references WHERE reference_id=?", String.class,
                UUID.fromString(staleCardReference))).isEqualTo("card");
        assertThat(jdbc.queryForObject("SELECT expires_at>now() AND expires_at<=now()+interval '10 minutes' FROM payment_method_references WHERE reference_id=?",
                Boolean.class, UUID.fromString(staleCardReference))).isTrue();

        jdbc.update("UPDATE orders SET shipping_country='DE' WHERE id=?", orderId);
        var wrongMarket = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", staleCardReference), ApiError.class, orderId);
        assertThat(wrongMarket.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        jdbc.update("UPDATE orders SET shipping_country='CH' WHERE id=?", orderId);
        jdbc.update("UPDATE payment_method_references SET expires_at=now()-interval '1 second' WHERE reference_id=?",
                UUID.fromString(staleCardReference));
        var expired = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", staleCardReference), ApiError.class, orderId);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        discovery = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        options = (List<Map>) discovery.getBody().get("methods");
        assertPaymentOptionShape(options);
        card = findPaymentOption(options, "Card");
        Map<?, ?> paypal = findPaymentOption(options, "PayPal");
        String cardReference = (String) card.get("reference");
        String paypalReference = (String) paypal.get("reference");

        var response = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", cardReference), Map.class, orderId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var retry = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", cardReference), Map.class, orderId);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("providerReference")).isEqualTo(response.getBody().get("providerReference"));
        var changedMethod = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", paypalReference), Map.class, orderId);
        assertThat(changedMethod.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT payment_method_id FROM payments WHERE order_id=? AND method='EXTENSION' AND payment_method_id='card'",
                String.class, orderId)).isEqualTo("card");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND method='EXTENSION' AND status='PENDING'",
                Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND method='EXTENSION' AND payment_method_id='paypal'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND method='EXTENSION' AND payment_obligation_id=?",
                Integer.class, orderId, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE order_id=? AND status='PENDING'", Integer.class, orderId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND payment_attempt_id IS NOT NULL", Integer.class, orderId)).isEqualTo(2);
    }

    @Test
    void paymentProviderReferencesAreNamespacedByProviderAccount() {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        String accountA = "0".repeat(64);
        String accountB = "1".repeat(64);
        insertExternalPayment(orderId, userId, "psp-a", accountA, "shared-provider-reference");
        insertExternalPayment(orderId, userId, "psp-a", accountB, "shared-provider-reference");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE provider_reference='shared-provider-reference'", Integer.class)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertExternalPayment(orderId, userId, "psp-a", accountA,
                "shared-provider-reference")).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void externalPaymentAttemptChargesOnlyTheBalanceRemainingAfterGiftCardTender() {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        jdbc.update("UPDATE orders SET total=12.50,currency='CHF',shipping_country='CH' WHERE id=?", orderId);
        reservePaymentTestOrder(orderId);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,2.50,'CHF','GIFT_CARD','CAPTURED',?,now(),now())",
                UUID.randomUUID(), orderId, userId, "giftcard-test-" + UUID.randomUUID());

        var discovered = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        List<Map> options = (List<Map>) discovered.getBody().get("methods");
        String cardReference = (String) findPaymentOption(options, "Card").get("reference");
        var checkout = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", cardReference), Map.class, orderId);

        assertThat(checkout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_attempts WHERE order_id=?", BigDecimal.class, orderId))
                .isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE order_id=? AND method='EXTENSION'", BigDecimal.class, orderId))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void concurrentCheckoutRetriesShareOneDurableProviderAttempt() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        jdbc.update("UPDATE orders SET total=12.50,currency='CHF',shipping_country='CH' WHERE id=?", orderId);
        reservePaymentTestOrder(orderId);
        var discovered = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        List<Map> options = (List<Map>) discovered.getBody().get("methods");
        String cardReference = (String) findPaymentOption(options, "Card").get("reference");
        var request = new HttpEntity<>(Map.of("methodReference", cardReference), bearerHeaders(adminToken));
        var ready = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.await(); return http.exchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST, request, Map.class, orderId); });
            var second = pool.submit(() -> { ready.await(); return http.exchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST, request, Map.class, orderId); });
            ready.countDown();
            var a = first.get();
            var b = second.get();
            assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(a.getBody().get("providerReference")).isEqualTo(b.getBody().get("providerReference"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND method='EXTENSION'", Integer.class, orderId)).isEqualTo(1);
    }

    @Test
    void ambiguousProviderFailureKeepsTheDurableAttemptForSameIdentityRetry() {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        jdbc.update("UPDATE orders SET total=12.50,currency='CHF',shipping_country='CH' WHERE id=?", orderId);
        reservePaymentTestOrder(orderId);
        var discovered = http.postForEntity("/api/v1/payments/methods", Map.of("country", "CH", "currency", "CHF"), Map.class);
        List<Map> options = (List<Map>) discovered.getBody().get("methods");
        String cardReference = (String) findPaymentOption(options, "Card").get("reference");
        PROVIDER_CHECKOUT_FAILURE.set(new IllegalStateException("simulated timeout with unknown provider outcome"));

        var ambiguous = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", cardReference), ApiError.class, orderId);
        assertThat(ambiguous.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempts WHERE order_id=?", String.class, orderId)).isEqualTo("UNKNOWN");

        var retry = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST,
                Map.of("methodReference", cardReference), Map.class, orderId);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempts WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_attempts WHERE order_id=?", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND method='EXTENSION'", Integer.class, orderId)).isEqualTo(1);
    }

    private HttpHeaders bearerHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private void reservePaymentTestOrder(UUID orderId) {
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Payment acceptance", "CH-ZH"));
        inventory.receive(warehouse.id(), sellableId, BigDecimal.ONE);
        inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(orderId, warehouse.id(), sellableId, BigDecimal.ONE));
    }

    private void insertExternalPayment(UUID orderId, UUID userId, String providerId, String accountFingerprint, String providerReference) {
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_plugin_id,provider_account_fingerprint,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,?,?,now(),now())",
                UUID.randomUUID(), orderId, userId, providerId, accountFingerprint, providerReference);
    }

    private static Map<?, ?> findPaymentOption(List<Map> options, String displayName) {
        return options.stream().filter(option -> displayName.equals(option.get("displayName"))).findFirst().orElseThrow();
    }

    private static void assertPaymentOptionShape(List<Map> options) {
        for (Map<?, ?> option : options) {
            assertThat(option.keySet()).hasSize(2);
            assertThat(option.containsKey("reference")).isTrue();
            assertThat(option.containsKey("displayName")).isTrue();
            assertThat(UUID.fromString((String) option.get("reference"))).isNotNull();
        }
    }

    @Test
    void expiryJobCancelsUnpaidOrdersOnlyAfterEveryReservationHasExpired() {
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Cancellation warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(BigDecimal.ONE),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);

        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID unpaidOrderId = insertPendingOrder(userId);
        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                unpaidOrderId, warehouseId, sellableId, BigDecimal.ONE));
        jdbc.update("UPDATE inventory_reservation SET expires_at=now()-interval '1 second' WHERE id=?", reservation.id());
        UUID paymentId = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                paymentId, unpaidOrderId, userId, "cs_expired_" + paymentId);

        reservationExpiryScheduler.releaseExpiredReservations();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, unpaidOrderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("FAILED");
        assertThat(inventory.reservationsByOrder(unpaidOrderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
    }

    @Test
    void providerExpiryWebhookCancelsOrderAndFailsItsPayment() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        UUID paymentId = UUID.randomUUID();
        String sessionId = "cs_expired_" + paymentId;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                paymentId, orderId, userId, sessionId);
        UUID secondPaymentId = UUID.randomUUID();
        String secondSessionId = "cs_second_" + secondPaymentId;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                secondPaymentId, orderId, userId, secondSessionId);

        var expired = providerWebhook("checkout.session.expired", sessionId, "unpaid");
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(PROVIDER_WEBHOOK_HEADERS.get()).containsEntry("x-signature", "postfinance-test-signature")
                .doesNotContainKey("cookie");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("FAILED");
        var duplicateExpiry = providerWebhook("checkout.session.expired", sessionId, "unpaid");
        assertThat(duplicateExpiry.getBody()).contains("duplicate");
        String expiryEventId = "evt_test_" + UUID.nameUUIDFromBytes(("checkout.session.expired:" + sessionId).getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_webhook_receipts WHERE provider='integration-test' AND event_id=?", Integer.class, expiryEventId)).isEqualTo(1);

        var lateSuccess = providerWebhook("checkout.session.completed", sessionId, "paid");
        assertThat(lateSuccess.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT disposition FROM payment_settlements WHERE payment_id=?", String.class, paymentId)).isEqualTo("APPLIED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE payment_settlements SET disposition='OVERPAYMENT' WHERE payment_id=?", paymentId))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("payment_settlements is immutable");

        providerWebhook("checkout.session.expired", secondSessionId, "unpaid");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("CAPTURED");

        UUID overpaidOrder = insertPendingOrder(userId);
        UUID overpaidPayment = UUID.randomUUID();
        String overpaidSession = "cs_overpaid_" + overpaidPayment;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                overpaidPayment, overpaidOrder, userId, overpaidSession);
        var overpayment = providerWebhook("checkout.session.completed", overpaidSession, "paid", "2.00", "USD");
        assertThat(overpayment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, overpaidOrder)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_settlements WHERE payment_id=?", BigDecimal.class, overpaidPayment))
                .isEqualByComparingTo("2.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE id=?", BigDecimal.class, overpaidPayment)).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("SELECT captured_amount FROM payments WHERE id=?", BigDecimal.class, overpaidPayment)).isEqualByComparingTo("2.00");
        assertThat(jdbc.queryForObject("SELECT case_type FROM payment_reconciliation_cases WHERE payment_id=?", String.class, overpaidPayment))
                .isEqualTo("OVERPAYMENT");
        var overpaymentRefundHeaders = new HttpHeaders();
        overpaymentRefundHeaders.setBearerAuth(adminToken);
        overpaymentRefundHeaders.set("Idempotency-Key", "overpayment-refund-" + overpaidPayment);
        var overpaymentRefund = http.exchange("/api/v1/payments/{paymentId}/refund", HttpMethod.POST,
                new HttpEntity<>(new io.artio.api.payments.PaymentController.RefundCommand(new BigDecimal("2.00")), overpaymentRefundHeaders),
                io.artio.api.payments.PaymentController.RefundView.class, overpaidPayment);
        assertThat(overpaymentRefund.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, overpaidPayment)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_reconciliation_cases WHERE payment_id=?", String.class, overpaidPayment)).isEqualTo("RESOLVED");
        assertThat(adminExchange("/api/v1/admin/payments/reconciliation-cases", HttpMethod.GET, null, List.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        UUID doublePaidOrder = insertPendingOrder(userId);
        UUID firstCapture = UUID.randomUUID();
        UUID secondCapture = UUID.randomUUID();
        String firstSession = "cs_double_first_" + firstCapture;
        String secondSession = "cs_double_second_" + secondCapture;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now()),(?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                firstCapture, doublePaidOrder, userId, firstSession, secondCapture, doublePaidOrder, userId, secondSession);
        providerWebhook("checkout.session.completed", secondSession, "paid");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, doublePaidOrder)).isEqualTo("PAID");
        providerWebhook("checkout.session.completed", firstSession, "paid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_reconciliation_cases WHERE order_id=? AND case_type='OVERPAYMENT'",
                Integer.class, doublePaidOrder)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_timeline_events WHERE order_id=? AND event_type='ORDER_PAID'",
                Integer.class, doublePaidOrder)).isEqualTo(1);

        UUID underpaidOrder = insertPendingOrder(userId);
        UUID underpaidPayment = UUID.randomUUID();
        String underpaidSession = "cs_underpaid_" + underpaidPayment;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                underpaidPayment, underpaidOrder, userId, underpaidSession);
        var underpayment = providerWebhook("checkout.session.completed", underpaidSession, "paid", "0.50", "USD");
        assertThat(underpayment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, underpaidPayment)).isEqualTo("CAPTURE_REVIEW");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, underpaidOrder)).isEqualTo("PENDING");
        var blockedRetry = adminExchange("/api/v1/payments/orders/{orderId}/checkout-session", HttpMethod.POST, null, ApiError.class, underpaidOrder);
        assertThat(blockedRetry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT case_type FROM payment_reconciliation_cases WHERE payment_id=?", String.class, underpaidPayment))
                .isEqualTo("UNDERPAYMENT");

        UUID cancelledOrder = insertPendingOrder(userId);
        UUID cancelledPayment = UUID.randomUUID();
        String cancelledSession = "cs_cancelled_late_" + cancelledPayment;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','EXTENSION','FAILED',?,now(),now())",
                cancelledPayment, cancelledOrder, userId, cancelledSession);
        jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", cancelledOrder);
        var cancelledLateCapture = providerWebhook("checkout.session.completed", cancelledSession, "paid");
        assertThat(cancelledLateCapture.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, cancelledOrder)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, cancelledPayment)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT case_type FROM payment_reconciliation_cases WHERE payment_id=?", String.class, cancelledPayment))
                .isEqualTo("LATE_CAPTURE");
    }

    @Test
    void verifiedDisputeAndReversalWebhooksCorrelateByProviderTransactionReference() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID wonOrder = insertPendingOrder(userId);
        UUID wonPayment = UUID.randomUUID();
        String wonSession = "cs_dispute_won_" + wonPayment;
        String wonTransaction = "pi_dispute_won_" + wonPayment;
        String wonDispute = "dp_won_" + wonPayment;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                wonPayment, wonOrder, userId, wonSession);
        providerWebhook("checkout.session.completed", wonSession, "paid", "1.00", "USD", wonTransaction);
        var disputeOpened = providerWebhook("payment.dispute.opened", wonDispute, "disputed", "1.00", "USD", wonTransaction);
        assertThat(disputeOpened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, wonPayment)).isEqualTo("CAPTURE_REVIEW");
        assertThat(jdbc.queryForObject("SELECT case_type FROM payment_reconciliation_cases WHERE payment_id=?", String.class, wonPayment)).isEqualTo("CHARGEBACK");
        assertThat(jdbc.queryForObject("SELECT provider_transaction_reference FROM payments WHERE id=?", String.class, wonPayment)).isEqualTo(wonTransaction);
        var blockedRefundHeaders = new HttpHeaders();
        blockedRefundHeaders.setBearerAuth(adminToken);
        blockedRefundHeaders.set("Idempotency-Key", "blocked-dispute-refund-" + wonPayment);
        var blockedRefund = http.exchange("/api/v1/payments/{paymentId}/refund", HttpMethod.POST,
                new HttpEntity<>(new io.artio.api.payments.PaymentController.RefundCommand(new BigDecimal("1.00")), blockedRefundHeaders),
                ApiError.class, wonPayment);
        assertThat(blockedRefund.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        providerWebhook("payment.dispute.won", wonDispute, "won", "1.00", "USD", wonTransaction);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, wonPayment)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_reconciliation_cases WHERE payment_id=?", String.class, wonPayment)).isEqualTo("RESOLVED");

        UUID lostOrder = insertPendingOrder(userId);
        UUID lostPayment = UUID.randomUUID();
        String lostSession = "cs_dispute_lost_" + lostPayment;
        String lostTransaction = "pi_dispute_lost_" + lostPayment;
        String lostDispute = "dp_lost_" + lostPayment;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) VALUES (?,?,?,1,'USD','EXTENSION','PENDING',?,now(),now())",
                lostPayment, lostOrder, userId, lostSession);
        providerWebhook("checkout.session.completed", lostSession, "paid", "1.00", "USD", lostTransaction);
        providerWebhook("payment.dispute.opened", lostDispute, "disputed", "1.00", "USD", lostTransaction);
        providerWebhook("payment.dispute.lost", lostDispute, "lost", "1.00", "USD", lostTransaction);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, lostPayment)).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT reversed_amount FROM payments WHERE id=?", BigDecimal.class, lostPayment)).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_settlements WHERE payment_id=? AND disposition='CHARGEBACK_LOST'", Integer.class, lostPayment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_reconciliation_cases WHERE payment_id=? AND case_type='CHARGEBACK'", String.class, lostPayment)).isEqualTo("OPEN");
    }

    @Test
    void retainsOrderOutboxEventAndSchedulesRetryWhenKafkaIsUnavailable() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,'{}'::jsonb)", eventId, UUID.randomUUID().toString());

        java.time.Instant publicationStartedAt = java.time.Instant.now();
        orderEventOutboxPublisher.publishPending();

        assertThat(jdbc.queryForObject("SELECT status FROM event_outbox WHERE id=?", String.class, eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE id=?", Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_at FROM event_outbox WHERE id=?", java.sql.Timestamp.class, eventId)
                .toInstant()).isAfter(publicationStartedAt);
        assertThat(jdbc.queryForObject("SELECT last_error IS NOT NULL FROM event_outbox WHERE id=?", Boolean.class, eventId)).isTrue();
    }

    @Test
    void movesRetryExhaustedEventsToDeadLetterState() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload,status,attempts) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,'{}'::jsonb,'PENDING',12)", eventId, UUID.randomUUID().toString());

        orderEventOutboxPublisher.publishPending();

        assertThat(jdbc.queryForObject("SELECT status FROM event_outbox WHERE id=?", String.class, eventId)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT dead_at IS NOT NULL FROM event_outbox WHERE id=?", Boolean.class, eventId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE id=?", Integer.class, eventId)).isEqualTo(12);
    }

    @Test
    void deadLetterEventsCanBeSafelyRedrivenByAdministrators() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload,status,attempts,dead_at,last_error) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,jsonb_build_object('payload','private-test-value'),'DEAD',12,now(),'TimeoutException')",
                eventId, aggregateId);

        var headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        var listed = http.exchange("/api/v1/admin/operations/outbox/events/dead", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).contains(eventId.toString(), "ORDER_CREATED", "TimeoutException")
                .doesNotContain("private-test-value");

        var replay = http.exchange("/api/v1/admin/operations/outbox/events/" + eventId + "/replay", HttpMethod.POST,
                new HttpEntity<>(headers), io.artio.api.notifications.OutboxOperationsController.ReplayResult.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM event_outbox WHERE id=?", String.class, eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE id=?", Integer.class, eventId)).isZero();
    }

    private ResponseEntity<String> providerWebhook(String type, String sessionId, String paymentStatus) throws Exception {
        return providerWebhook(type, sessionId, paymentStatus, "1.00", "USD");
    }

    private ResponseEntity<String> providerWebhook(String type, String sessionId, String paymentStatus, String amount, String currency) throws Exception {
        return providerWebhook(type,sessionId,paymentStatus,amount,currency,null);
    }

    private ResponseEntity<String> providerWebhook(String type,String sessionId,String paymentStatus,String amount,String currency,String transactionReference) throws Exception {
        String eventId = "evt_test_" + UUID.nameUUIDFromBytes((type + ":" + sessionId).getBytes(StandardCharsets.UTF_8));
        String body = "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + sessionId
                + "\",\"payment_status\":\"" + paymentStatus + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency + "\""
                + (transactionReference==null?"":",\"transactionReference\":\""+transactionReference+"\"") + "}}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Provider-Signature", "integration-test-signature");
        headers.set("X-Signature", "postfinance-test-signature");
        return http.exchange("/api/v1/payments/provider/webhook", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private UUID insertPendingOrder(UUID userId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,"
                        + "shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',1,0,0,1,'USD','Test address','Test address',now(),'US','US','US','{}'::jsonb,?)",
                orderId, userId, "expiry-test-" + orderId);
        return orderId;
    }

    @Test
    void concurrentReservationsCannotOversellSharedStock() throws Exception {
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "CONC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Concurrency warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(warehouse.getBody()).isNotNull();
        assertThat(warehouse.getBody().id()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("5")),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);

        var first = CompletableFuture.supplyAsync(() -> reserveIfAvailable(UUID.randomUUID(), warehouseId, sellableId));
        var second = CompletableFuture.supplyAsync(() -> reserveIfAvailable(UUID.randomUUID(), warehouseId, sellableId));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(true, false);
        assertThat(inventory.stockBySellable(sellableId)).singleElement().satisfies(stock -> {
            assertThat(stock.onHand()).isEqualByComparingTo("5");
            assertThat(stock.reserved()).isEqualByComparingTo("3");
            assertThat(stock.available()).isEqualByComparingTo("2");
        });

        var adjusted=adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}",HttpMethod.PUT,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("6"),"Cycle count correction"),
                io.artio.api.inventory.InventoryApplicationService.StockView.class,warehouseId,sellableId);
        assertThat(adjusted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adjusted.getBody()).isNotNull();
        assertThat(adjusted.getBody().onHand()).isEqualByComparingTo("6");
        var movements=adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/movements?limit=20",HttpMethod.GET,null,
                io.artio.inventory.StockMovement[].class,warehouseId,sellableId);
        assertThat(movements.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(movements.getBody()).extracting("type").contains("RECEIPT","RESERVATION","ADJUSTMENT");
        assertThat(movements.getBody()).filteredOn(movement->movement.type().equals("ADJUSTMENT"))
                .singleElement().satisfies(movement->{assertThat(movement.reason()).isEqualTo("Cycle count correction");assertThat(movement.actorId()).isEqualTo(adminUserId);});
    }

    @Test
    void extendsReservedStockThroughThePaymentSessionWindow() {
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Payment warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(warehouse.getBody()).isNotNull();
        assertThat(warehouse.getBody().id()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(BigDecimal.ONE),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);
        var initial = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouseId, sellableId, BigDecimal.ONE));
        var minimumExpiry = java.time.Instant.now().plus(java.time.Duration.ofMinutes(31));

        var actualExpiry = inventory.extendReservationsForPayment(orderId, minimumExpiry);

        assertThat(actualExpiry).isAfterOrEqualTo(minimumExpiry);
        assertThat(inventory.reservationsByOrder(orderId)).singleElement().satisfies(reservation -> {
            assertThat(reservation.id()).isEqualTo(initial.id());
            assertThat(reservation.state()).isEqualTo(io.artio.inventory.ReservationState.RESERVED);
            assertThat(reservation.expiresAt()).isEqualTo(actualExpiry);
        });
        assertThat(inventory.stockBySellable(sellableId)).singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualByComparingTo("0"));
    }

    @Test
    void appliesSeededSwissVatBySellableTaxCategory() throws Exception {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Swiss tea", null, "CH-TEA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), ProductResponse.class);
        assertThat(product.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(product.getBody()).isNotNull();
        assertThat(product.getBody().id()).isNotNull();
        var variant = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("250 g", "CH-TEA-V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT)), VariantResponse.class,
                product.getBody().id());
        assertThat(variant.getStatusCode()).withFailMessage("Swiss variant creation: %s", variant.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(variant.getBody()).isNotNull();
        assertThat(variant.getBody().id()).isNotNull();
        var swissPrice = adminExchange("/api/v1/prices", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.PriceRequest(variant.getBody().id(), "CH", "WEB",
                        new BigDecimal("100.00"), "CHF", java.time.Instant.now().minusSeconds(60), null),
                String.class);
        assertThat(swissPrice.getStatusCode()).withFailMessage("Swiss price creation: %s", swissPrice.getBody()).isEqualTo(HttpStatus.CREATED);

        var standard = http.postForEntity("/api/v1/pricing/quote",
                new io.artio.api.pricing.PricingApplicationService.QuoteRequest(variant.getBody().id(), "CH", "WEB", "CH", BigDecimal.ONE, null),
                String.class);
        assertThat(standard.getStatusCode()).withFailMessage("Swiss pricing response: %s", standard.getBody()).isEqualTo(HttpStatus.OK);
        var standardQuote=mapper.readValue(standard.getBody(),io.artio.pricing.PricingQuote.class);
        assertThat(standardQuote.taxCategory()).isEqualTo(io.artio.pricing.TaxCategory.STANDARD);
        assertThat(standardQuote.tax().amount()).isEqualByComparingTo("8.10");

        adminExchange("/api/v1/tax-rules/sellables/{sellableId}/category", HttpMethod.PUT,
                new io.artio.api.pricing.PricingController.TaxCategoryRequest(io.artio.pricing.TaxCategory.REDUCED),
                io.artio.api.pricing.PricingController.TaxCategoryView.class, variant.getBody().id());
        var assignments = adminExchange("/api/v1/tax-rules/sellables/categories", HttpMethod.GET, null,
                io.artio.api.pricing.PricingController.TaxCategoryView[].class);
        assertThat(assignments.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assignments.getBody()).anySatisfy(assignment -> {
            assertThat(assignment.sellableId()).isEqualTo(variant.getBody().id());
            assertThat(assignment.category()).isEqualTo(io.artio.pricing.TaxCategory.REDUCED);
        });
        var reduced = http.postForEntity("/api/v1/pricing/quote",
                new io.artio.api.pricing.PricingApplicationService.QuoteRequest(variant.getBody().id(), "CH", "WEB", "CH", BigDecimal.ONE, null),
                String.class);
        assertThat(reduced.getStatusCode()).withFailMessage("Reduced Swiss pricing response: %s",reduced.getBody()).isEqualTo(HttpStatus.OK);
        var reducedQuote=mapper.readValue(reduced.getBody(),io.artio.pricing.PricingQuote.class);
        assertThat(reducedQuote.taxCategory()).isEqualTo(io.artio.pricing.TaxCategory.REDUCED);
        assertThat(reducedQuote.tax().amount()).isEqualByComparingTo("2.60");
    }

    @Test
    void bundleApiQuotesMultipleSelectionGroupsAndBothBundlePriceModes() {
        var suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Bundle quote item", null, "BUNDLE-P-" + suffix), ProductResponse.class);
        var first = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Primary", "BUNDLE-A-" + suffix), VariantResponse.class,
                product.getBody().id());
        var second = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Optional", "BUNDLE-B-" + suffix), VariantResponse.class,
                product.getBody().id());
        for (var variant : List.of(first.getBody(), second.getBody())) {
            var saved = adminExchange("/api/v1/prices", HttpMethod.POST,
                    new io.artio.api.pricing.PricingApplicationService.PriceRequest(variant.id(), "US", "WEB",
                            new BigDecimal(variant.id().equals(first.getBody().id()) ? "10.00" : "5.00"), "USD",
                            java.time.Instant.now().minusSeconds(60), null),
                    io.artio.api.pricing.PricingApplicationService.PriceView.class);
            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        var percentageBundle = adminExchange("/api/v1/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Configured set", "BND-C-" + suffix,
                        io.artio.bundles.BundleType.CONFIGURABLE, List.of(), List.of(
                                new io.artio.api.bundles.BundleApplicationService.OptionGroupRequest("primary", "Primary", 1, 1,
                                        List.of(new io.artio.api.bundles.BundleApplicationService.ChoiceRequest(first.getBody().id(), BigDecimal.ONE))),
                                new io.artio.api.bundles.BundleApplicationService.OptionGroupRequest("optional", "Optional", 0, 1,
                                        List.of(new io.artio.api.bundles.BundleApplicationService.ChoiceRequest(second.getBody().id(), new BigDecimal("2"))))),
                        new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.PERCENTAGE_OFF_COMPONENTS, null, null, new BigDecimal("10"))),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        assertThat(percentageBundle.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var quote = http.postForEntity("/api/v1/bundles/{id}/quote",
                new io.artio.api.bundles.BundleApplicationService.BundleQuoteRequest(
                        Map.of("primary", Set.of(first.getBody().id()), "optional", Set.of(second.getBody().id())),
                        new BigDecimal("2"), "US", "WEB", "US"),
                io.artio.bundles.BundleQuote.class, percentageBundle.getBody().id());
        assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(quote.getBody().componentTotal().amount()).isEqualByComparingTo("20.00");
        assertThat(quote.getBody().perBundlePrice().amount()).isEqualByComparingTo("18.00");
        assertThat(quote.getBody().totalPrice().amount()).isEqualByComparingTo("36.00");

        var fixedBundle = adminExchange("/api/v1/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Fixed set", "BND-F-" + suffix,
                        io.artio.bundles.BundleType.FIXED,
                        List.of(new io.artio.api.bundles.BundleApplicationService.ComponentRequest(first.getBody().id(), BigDecimal.ONE)),
                        List.of(), new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.FIXED_PRICE, new BigDecimal("7.50"), "USD", null)),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        var fixedQuote = http.postForEntity("/api/v1/bundles/{id}/quote",
                new io.artio.api.bundles.BundleApplicationService.BundleQuoteRequest(Map.of(), BigDecimal.ONE, "US", "WEB", "US"),
                io.artio.bundles.BundleQuote.class, fixedBundle.getBody().id());
        assertThat(fixedQuote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixedQuote.getBody().totalPrice().amount()).isEqualByComparingTo("7.50");

        var europeanPrice = adminExchange("/api/v1/prices", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.PriceRequest(first.getBody().id(), "DE", "WEB",
                        new BigDecimal("12.00"), "EUR", java.time.Instant.now().minusSeconds(60), null),
                io.artio.api.pricing.PricingApplicationService.PriceView.class);
        assertThat(europeanPrice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var marketFixedBundle = adminExchange("/api/v1/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Market fixed set", "BND-M-" + suffix,
                        io.artio.bundles.BundleType.FIXED,
                        List.of(new io.artio.api.bundles.BundleApplicationService.ComponentRequest(first.getBody().id(), BigDecimal.ONE)),
                        List.of(), new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.FIXED_PRICE, new BigDecimal("7.50"), "USD", null,
                                Map.of("DE", new io.artio.api.bundles.BundleApplicationService.MoneyRequest(new BigDecimal("8.25"), "EUR")))),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        var germanQuote = http.postForEntity("/api/v1/bundles/{id}/quote",
                new io.artio.api.bundles.BundleApplicationService.BundleQuoteRequest(Map.of(), BigDecimal.ONE, "DE", "WEB", "DE"),
                io.artio.bundles.BundleQuote.class, marketFixedBundle.getBody().id());
        assertThat(germanQuote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(germanQuote.getBody().totalPrice().amount()).isEqualByComparingTo("8.25");
        assertThat(germanQuote.getBody().totalPrice().currency().getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    void activeTaxRulesCannotOverlapWithinJurisdictionAndCategory() {
        var from = java.time.Instant.now().plusSeconds(86_400);
        var middle = from.plusSeconds(86_400);
        var until = middle.plusSeconds(86_400);
        var first = adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(
                        "ZZ-OVERLAP", new BigDecimal("7.5"), from, until,
                        io.artio.pricing.TaxCategory.STANDARD),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var overlap = adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(
                        "ZZ-OVERLAP", new BigDecimal("8.0"), middle, null,
                        io.artio.pricing.TaxCategory.STANDARD),
                String.class);
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var adjacent = adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(
                        "ZZ-OVERLAP", new BigDecimal("8.0"), until, null,
                        io.artio.pricing.TaxCategory.STANDARD),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        assertThat(adjacent.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var otherCategory = adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(
                        "ZZ-OVERLAP", new BigDecimal("2.0"), from, null,
                        io.artio.pricing.TaxCategory.REDUCED),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        assertThat(otherCategory.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void taxRulesCanBeEditedAndDeleted() {
        var jurisdiction = "ZZ-EDIT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        var from = java.time.Instant.now().minusSeconds(60);
        var created = adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(jurisdiction,
                        new BigDecimal("7.5"), from, null, io.artio.pricing.TaxCategory.STANDARD),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = created.getBody().id();

        var updated = adminExchange("/api/v1/tax-rules/{id}", HttpMethod.PUT,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest(jurisdiction,
                        new BigDecimal("2.5"), from, null, io.artio.pricing.TaxCategory.REDUCED),
                io.artio.api.pricing.PricingApplicationService.TaxView.class, id);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().percentage()).isEqualByComparingTo("2.5");
        assertThat(updated.getBody().category()).isEqualTo(io.artio.pricing.TaxCategory.REDUCED);

        var deleted = adminExchange("/api/v1/tax-rules/{id}", HttpMethod.DELETE, null, Void.class, id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var remaining = adminExchange("/api/v1/tax-rules", HttpMethod.GET, null,
                io.artio.api.pricing.PricingApplicationService.TaxView[].class);
        assertThat(java.util.Arrays.stream(remaining.getBody()).noneMatch(rule -> rule.id().equals(id))).isTrue();
    }

    private boolean reserveIfAvailable(UUID orderId, UUID warehouseId, UUID sellableId) {
        try {
            inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                    orderId, warehouseId, sellableId, new BigDecimal("3")));
            return true;
        } catch (IllegalStateException insufficientStock) {
            return false;
        }
    }

    @Test
    void checkoutIsIdempotentAndPersistsTaxProvenanceSnapshot() throws Exception {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Tax snapshot item", "Taxable", "TAX-SNAP-1"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();
        var variant = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Standard", "TAX-SNAP-1-STD"), VariantResponse.class, product.getBody().id());
        assertThat(variant.getBody()).isNotNull();
        adminExchange("/api/v1/prices", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.PriceRequest(variant.getBody().id(), "US", "WEB",
                        new BigDecimal("50.00"), "USD", java.time.Instant.now().minusSeconds(3600), null),
                io.artio.api.pricing.PricingApplicationService.PriceView.class);
        adminExchange("/api/v1/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest("US", new BigDecimal("8.25"),
                        java.time.Instant.now().minusSeconds(3600), null),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        var warehouse = adminExchange("/api/v1/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("IDEM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Checkout", "US"),
                String.class);
        assertThat(warehouse.getStatusCode()).withFailMessage("Warehouse response: %s", warehouse.getBody()).isEqualTo(HttpStatus.CREATED);
        var warehouseView = readWarehouse(warehouse.getBody());
        var received = adminExchange("/api/v1/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("10")),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseView.id(), variant.getBody().id());
        assertThat(received.getStatusCode()).withFailMessage("Receipt response: %s", received.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().available()).isEqualByComparingTo("10");
        var bundleResponse = adminExchange("/api/v1/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Snapshot bundle", "SNAP-BUNDLE-1",
                        io.artio.bundles.BundleType.FIXED,
                        List.of(new io.artio.api.bundles.BundleApplicationService.ComponentRequest(variant.getBody().id(), BigDecimal.ONE)),
                        List.of(), new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.COMPONENT_SUM, null, null, null)),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        assertThat(bundleResponse.getBody()).isNotNull();
        adminExchange("/api/v1/bundles/{id}/active", HttpMethod.PUT,
                new io.artio.api.bundles.BundleController.ActiveRequest(true),
                io.artio.api.bundles.BundleApplicationService.BundleView.class, bundleResponse.getBody().id());
        adminExchange("/api/v1/cart/bundles", HttpMethod.POST,
                new io.artio.api.orders.CartController.AddBundleRequest(bundleResponse.getBody().id(), Map.of(),
                        new BigDecimal("2"), "US", "WEB", "US"),
                io.artio.api.orders.CartController.CartView.class);

        String key = UUID.randomUUID().toString();
        var headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        headers.set("Idempotency-Key", key);
        var body = new io.artio.api.orders.CartController.CheckoutRequest("1 Main Street, Zurich", null, "US", "de-CH");
        var first = http.exchange("/api/v1/cart/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(first.getStatusCode()).withFailMessage("Checkout response: %s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        var firstBody = readOrder(first.getBody());
        assertThat(firstBody.tax()).isEqualByComparingTo("8.25");
        assertThat(firstBody.taxSnapshot()).containsEntry("jurisdiction", "US");
        assertThat(firstBody.taxSnapshot()).containsKey("bundles");
        JsonNode taxSnapshot = mapper.valueToTree(firstBody.taxSnapshot());
        assertThat(taxSnapshot.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(taxSnapshot.path("lines").get(0).path("taxCategory").asText()).isEqualTo("STANDARD");
        assertThat(mapper.writeValueAsString(firstBody.taxSnapshot().get("bundles")))
                .contains(bundleResponse.getBody().id().toString(), variant.getBody().id().toString(), "configuredTotal", "componentAdjustmentFactor");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id=? AND event_type='ORDER_CREATED'", Integer.class,
                firstBody.orderId().toString())).isEqualTo(1);
        String eventPayload = jdbc.queryForObject("SELECT payload::text FROM event_outbox WHERE aggregate_id=? AND event_type='ORDER_CREATED'",
                String.class, firstBody.orderId().toString());
        assertThat(mapper.readTree(eventPayload).path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(eventPayload).path("locale").asText()).isEqualTo("de-CH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + firstBody.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE dedupe_key=?", Integer.class,
                "order-created:" + firstBody.orderId())).isEqualTo(1);

        var replay = http.exchange("/api/v1/cart/checkout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readOrder(replay.getBody()).orderId()).isEqualTo(firstBody.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key=?", Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id=?", Integer.class,
                firstBody.orderId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery_outbox WHERE event_key=?", Integer.class,
                "order-email:" + firstBody.orderId())).isZero();
    }

    @Test
    void bundleConfiguratorResolvesLocalizedLabelsWithEnglishAndBaseFallback() {
        var created = adminExchange("/api/v1/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Choose your items", "LOCAL-BUNDLE-1",
                        io.artio.bundles.BundleType.CONFIGURABLE, List.of(),
                        List.of(new io.artio.api.bundles.BundleApplicationService.OptionGroupRequest("choices",
                                "Select components", 1, 1,
                                List.of(new io.artio.api.bundles.BundleApplicationService.ChoiceRequest(UUID.randomUUID(), BigDecimal.ONE)))),
                        new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.COMPONENT_SUM, null, null, null)),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID bundleId = created.getBody().id();
        var saved = adminExchange("/api/v1/bundles/{id}/translations/{locale}", HttpMethod.PUT,
                new io.artio.api.bundles.BundleLocalizationApplicationService.TranslationRequest("Wähle deine Artikel",
                        Map.of("choices", "Komponenten auswählen")),
                io.artio.api.bundles.BundleLocalizationApplicationService.TranslationView.class, bundleId, "de");
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_bundle_option_group_translations WHERE bundle_id=? AND group_code='choices' AND locale='de'", Integer.class, bundleId)).isEqualTo(1);
        assertThat(saved.getBody().optionGroupNames()).containsEntry("choices", "Komponenten auswählen");

        var german = http.getForObject("/api/v1/bundles/{id}/localized?locale=de",
                io.artio.api.bundles.BundleLocalizationApplicationService.LocalizedBundleView.class, bundleId);
        assertThat(german.localizedName()).isEqualTo("Wähle deine Artikel");
        assertThat(german.optionGroups()).singleElement().extracting(
                io.artio.api.bundles.BundleLocalizationApplicationService.LocalizedOptionGroup::localizedName)
                .isEqualTo("Komponenten auswählen");
        adminExchange("/api/v1/bundles/{id}/translations/{locale}", HttpMethod.PUT,
                new io.artio.api.bundles.BundleLocalizationApplicationService.TranslationRequest("Bundle contents", Map.of()),
                io.artio.api.bundles.BundleLocalizationApplicationService.TranslationView.class, bundleId, "en");
        var italian = http.getForObject("/api/v1/bundles/{id}/localized?locale=it",
                io.artio.api.bundles.BundleLocalizationApplicationService.LocalizedBundleView.class, bundleId);
        assertThat(italian.localizedName()).isEqualTo("Bundle contents");
        assertThat(italian.optionGroups()).singleElement().extracting(
                io.artio.api.bundles.BundleLocalizationApplicationService.LocalizedOptionGroup::localizedName)
                .isEqualTo("Select components");
    }

    @Test
    void outboxDiagnosticsAreAdminOnlyAndExposeAggregateCounts() {
        long oldProcessedReceipts = jdbc.queryForObject("SELECT count(*) FROM payment_webhook_receipts WHERE processed_at IS NOT NULL AND processed_at < now()-interval '90 days'", Long.class);
        String eventPrefix = "diagnostic-" + UUID.randomUUID();
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('EXTENSION',?,'completed',now()-interval '120 days',now()-interval '5 days')", eventPrefix + "-recently-processed");
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('EXTENSION',?,'completed',now()-interval '1 day',now()-interval '120 days')", eventPrefix + "-old-processed");
        var anonymous = http.getForEntity("/api/v1/admin/operations/outbox", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        var response = http.exchange("/api/v1/admin/operations/outbox", HttpMethod.GET,
                new HttpEntity<>(headers), io.artio.api.notifications.OutboxOperationsController.OutboxSummary.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().eventPending()).isGreaterThanOrEqualTo(0);
        assertThat(response.getBody().emailDead()).isGreaterThanOrEqualTo(0);
        assertThat(response.getBody().webhookUnprocessed()).isGreaterThanOrEqualTo(0);
        assertThat(response.getBody().webhookOlderThanRetention()).isEqualTo(oldProcessedReceipts + 1);
    }

    @Test
    void kafkaDeadLettersAreAdminOnlyPayloadFreeAndReplayable() {
        UUID deadLetterId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String sensitivePayload = "private-kafka-payload-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO kafka_consumer_dead_letters(id,source_topic,source_partition,source_offset,event_id,event_type,
                    record_key,encrypted_payload,payload_is_null,schema_version,error_class)
                VALUES (?,?,0,?,?,?,?,?,false,'1','IllegalArgumentException')
                """, deadLetterId, "artio.order.events", 100L, eventId, "ORDER_CREATED", "order-" + eventId,
                encryption.encrypt(sensitivePayload));

        var anonymous = http.getForEntity("/api/v1/admin/operations/kafka/dead-letters/items", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String items = adminExchange("/api/v1/admin/operations/kafka/dead-letters/items", HttpMethod.GET, null, String.class).getBody();
        assertThat(items).contains(eventId).doesNotContain(sensitivePayload).doesNotContain("encryptedPayload");

        var replay = adminExchange("/api/v1/admin/operations/kafka/dead-letters/{id}/replay", HttpMethod.POST, null,
                io.artio.api.notifications.KafkaDeadLetterOperationsController.ReplayAccepted.class, deadLetterId);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM kafka_consumer_dead_letters WHERE id=?", String.class, deadLetterId))
                .isEqualTo("REPLAY_REQUESTED");
        var duplicateReplay = adminExchange("/api/v1/admin/operations/kafka/dead-letters/{id}/replay", HttpMethod.POST,
                null, ApiError.class, deadLetterId);
        assertThat(duplicateReplay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reconciliationMarksLostPaidProviderSessionAsCapturedAndPaid() {
        UUID orderId = createStaleProviderOrder("cs_reconcile_paid_" + UUID.randomUUID());
        PROVIDER_RESPONSE.set("{\"status\":\"complete\",\"payment_status\":\"paid\",\"amount\":\"12.50\",\"currency\":\"CHF\"}");

        paymentReconciliationWorker.reconcileDueSessions();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT reconciliation_claimed_at IS NULL FROM payments WHERE order_id=?", Boolean.class, orderId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT disposition FROM payment_settlements WHERE payment_id=(SELECT id FROM payments WHERE order_id=?)",
                String.class, orderId)).isEqualTo("APPLIED");
    }

    @Test
    void reconciliationRecordsCapturedOverpaymentForOperatorResolution() {
        UUID orderId = createStaleProviderOrder("cs_reconcile_overpaid_" + UUID.randomUUID());
        PROVIDER_RESPONSE.set("{\"status\":\"complete\",\"payment_status\":\"paid\",\"amount\":\"20.00\",\"currency\":\"CHF\"}");

        paymentReconciliationWorker.reconcileDueSessions();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_settlements WHERE payment_id=(SELECT id FROM payments WHERE order_id=?)",
                BigDecimal.class, orderId)).isEqualByComparingTo("20.00");
        assertThat(jdbc.queryForObject("SELECT case_type FROM payment_reconciliation_cases WHERE order_id=?", String.class, orderId))
                .isEqualTo("OVERPAYMENT");
    }

    @Test
    void reconciliationExpiresLostProviderSessionAndReleasesReservations() {
        UUID orderId = createStaleProviderOrder("cs_reconcile_expired_" + UUID.randomUUID());
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "RECON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Reconciliation", "CH-ZH"));
        inventory.receive(warehouse.id(), sellableId, BigDecimal.ONE);
        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouse.id(), sellableId, BigDecimal.ONE));
        PROVIDER_RESPONSE.set("{\"status\":\"expired\",\"payment_status\":\"unpaid\"}");

        paymentReconciliationWorker.reconcileDueSessions();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId)).isEqualTo("FAILED");
        assertThat(inventory.reservationsByOrder(orderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
    }

    private UUID createStaleProviderOrder(String sessionId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',12.50,0,0,12.50,'CHF','address','address',now()-interval '45 minutes','CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "reconciliation-" + orderId);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,12.50,'CHF','EXTENSION','PENDING',?,now()-interval '45 minutes',now())",
                UUID.randomUUID(), orderId, adminUserId, sessionId);
        return orderId;
    }

    @Test
    void mailOutboxWorkerDeliversAndRedactsSuccessfulDelivery() {
        UUID orderId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body) VALUES (?,?,?,?,?,?)",
                deliveryId, "integration-email:" + deliveryId, adminUserId, orderId, "Test order", "Thank you for your order");
        org.springframework.beans.factory.ObjectProvider<io.artio.extensions.commerce.NotificationTransport> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(
                message -> new io.artio.extensions.commerce.NotificationTransport.DeliveryResult(true, "test-reference", null, null));

        new io.artio.api.notifications.OrderNotificationHandler(jdbc, provider, true)
                .deliverPendingBatch();

        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT subject || body FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEmpty();
    }

    @Test
    void mailOutboxWorkerRetriesTransientFailureWithBackoff() {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body) VALUES (?,?,?,?,?,?)",
                deliveryId, "integration-email-retry:" + deliveryId, adminUserId, UUID.randomUUID(), "Retry order", "Retry body");
        org.springframework.beans.factory.ObjectProvider<io.artio.extensions.commerce.NotificationTransport> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(
                message -> { throw new IllegalStateException("simulated provider failure"); });

        new io.artio.api.notifications.OrderNotificationHandler(jdbc, provider, true)
                .deliverPendingBatch();

        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_delivery_outbox WHERE id=?", Integer.class, deliveryId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at>now() FROM notification_delivery_outbox WHERE id=?", Boolean.class, deliveryId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error_class FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId))
                .isEqualTo("IllegalStateException");
        assertThat(jdbc.queryForObject("SELECT body FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId))
                .isEqualTo("Retry body");
    }

    @Test
    void notificationOutboxRemainsDurableWhenNoPluginIsSelected() {
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_delivery_outbox(id,event_key,user_id,order_id,subject,body) VALUES (?,?,?,?,?,?)",
                deliveryId, "integration-email-unconfigured:" + deliveryId, adminUserId, UUID.randomUUID(), "Queued order", "Queued body");
        org.springframework.beans.factory.ObjectProvider<io.artio.extensions.commerce.NotificationTransport> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(null);

        new io.artio.api.notifications.OrderNotificationHandler(jdbc, provider, true).deliverPendingBatch();

        assertThat(jdbc.queryForObject("SELECT status FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_delivery_outbox WHERE id=?", Integer.class, deliveryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT subject || body FROM notification_delivery_outbox WHERE id=?", String.class, deliveryId)).isEqualTo("Queued orderQueued body");
    }

    private io.artio.api.orders.CartController.OrderView readOrder(String json) {
        try { return mapper.readValue(json, io.artio.api.orders.CartController.OrderView.class); }
        catch (Exception e) { throw new AssertionError("Could not decode checkout response: " + json, e); }
    }

    private io.artio.api.inventory.InventoryApplicationService.WarehouseView readWarehouse(String json) {
        try { return mapper.readValue(json, io.artio.api.inventory.InventoryApplicationService.WarehouseView.class); }
        catch (Exception e) { throw new AssertionError("Could not decode warehouse response: " + json, e); }
    }

    @Test
    void persistsAndChangesVariantsUnderTheirProduct() {
        var product = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Laptop", "Portable computer", "LAP-0001"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();

        var createdVariant = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("16 GB / 512 GB", "LAP-0001-16-512"), VariantResponse.class,
                product.getBody().id());

        assertThat(createdVariant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createdVariant.getBody()).isNotNull();
        assertThat(createdVariant.getBody().productId()).isEqualTo(product.getBody().id());

        ResponseEntity<VariantResponse[]> variants = http.getForEntity(
                "/api/v1/products/{id}/variants", VariantResponse[].class, product.getBody().id());
        assertThat(variants.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(variants.getBody()).hasSize(1);
        assertThat(variants.getBody()[0].sku()).isEqualTo("LAP-0001-16-512");

        var variantId = createdVariant.getBody().id();
        var anonymousStatusChange=http.exchange("/api/v1/variants/{id}/active",HttpMethod.PUT,
                new HttpEntity<>(new SetActiveRequest(false)),String.class,variantId);
        assertThat(anonymousStatusChange.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        adminExchange("/api/v1/variants/{id}/active", HttpMethod.PUT, new SetActiveRequest(false), String.class, variantId);
        var inactive = http.getForEntity("/api/v1/variants/{id}", VariantResponse.class, variantId);
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void rejectsInvalidProductsDuplicateSkusAndMissingParents() {
        var invalid = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest(" ", null, "MON-2702"), ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Monitor", null, "DUP-100"), ProductResponse.class);
        var duplicate = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Second monitor", null, "DUP-100"), ApiError.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var missingParent = adminExchange("/api/v1/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Blue", "ITEM-BLU"), ApiError.class, UUID.randomUUID());
        assertThat(missingParent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void installsSingleCurrentApiSchemaWithoutTenantSchema() {
        var appliedVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank", String.class);
        assertThat(appliedVersions).containsExactly("1");

        var enterpriseTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name IN "
                        + "('users','mfa_methods','oauth_identities',"
                        + "'audit_logs','consent_records','gift_cards','loyalty_accounts','subscriptions')",
                Integer.class);
        assertThat(enterpriseTables).isEqualTo(8);

        var inventoryMovementTable=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=current_schema() AND table_name='inventory_stock_movement'",Integer.class);
        assertThat(inventoryMovementTable).isEqualTo(1);

        var storedValueColumns = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND ((table_name='payments' AND column_name='gift_card_id') "
                + "OR (table_name='gift_card_transactions' AND column_name='payment_refund_id'))", Integer.class);
        assertThat(storedValueColumns).isEqualTo(2);
        var storedValueIndexes = jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes "
                + "WHERE schemaname=current_schema() AND indexname IN "
                + "('uq_gift_card_redemption_per_order','uq_gift_card_refund_transaction')", Integer.class);
        assertThat(storedValueIndexes).isEqualTo(2);

        var tenantTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=current_schema() AND table_name IN ('tenants','tenant_memberships')", Integer.class);
        var tenantColumn = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=current_schema() AND table_name='users' AND column_name='tenant_id'", Integer.class);
        assertThat(tenantTables).isZero();
        assertThat(tenantColumn).isZero();
    }

    @Test
    void giftCardAdminCanIssueInspectDisableAndReadLedgerWithoutExposingCodeHash() {
        var issued = adminExchange("/api/v1/admin/gift-cards", HttpMethod.POST,
                new io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardRequest(new BigDecimal("25.00"), "CHF", null),
                io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardIssued.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody()).isNotNull();
        var card = issued.getBody();
        assertThat(card.code()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT code_hash FROM gift_cards WHERE id=?", String.class, card.id())).isNotEqualTo(card.code());

        var listed = adminExchange("/api/v1/admin/gift-cards", HttpMethod.GET, null,
                io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardAdminView[].class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting("id").contains(card.id());
        assertThat(listed.getBody()[0].toString()).doesNotContain(card.code());

        var ledger = adminExchange("/api/v1/admin/gift-cards/{id}/transactions", HttpMethod.GET, null,
                io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardTransactionView[].class, card.id());
        assertThat(ledger.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ledger.getBody()).isEmpty();

        var disabled = adminExchange("/api/v1/admin/gift-cards/{id}/active", HttpMethod.PUT,
                new io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardActivation(false),
                io.artio.api.enterprise.EnterpriseFeaturesController.GiftCardAdminView.class, card.id());
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).isNotNull();
        assertThat(disabled.getBody().active()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE user_id=? AND action='GIFT_CARD_OFF'", Integer.class, adminUserId)).isEqualTo(1);
    }

    @Test
    void requiresAuthenticationForMfaEnrollment() {
        var response = http.postForEntity("/api/v1/auth/mfa/totp/enrollment", Map.of(), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
    }

    @Test
    void customerAdministrationSearchesByLastLoginActivityAndBoundsResults() {
        UUID customerId = UUID.randomUUID();
        String customerEmail = "recent-customer-" + customerId + "@example.test";
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at,last_login) "
                        + "VALUES (?,?,'fixture-hash','Recent','Customer','CUSTOMER',now(),now())", customerId, customerEmail);
        String base="/api/v1/admin/users";
        var recent=adminExchange(base+"?q="+customerEmail+"&activity=RECENT",HttpMethod.GET,null,
                io.artio.api.users.UserController.UserView[].class);
        assertThat(recent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recent.getBody()).hasSize(1);
        assertThat(recent.getBody()[0].id()).isEqualTo(customerId);
        assertThat(recent.getBody()[0].lastLogin()).isNotNull();

        var never=adminExchange(base+"?activity=NEVER",HttpMethod.GET,null,io.artio.api.users.UserController.UserView[].class);
        assertThat(never.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(never.getBody()).extracting("id").doesNotContain(customerId);
        var invalid=adminExchange(base+"?limit=201",HttpMethod.GET,null,ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void versionedRoutesKeepPublicAndPrivilegedAuthorizationBoundaries() {
        var publicProducts = http.getForEntity("/api/v1/products", String.class);
        assertThat(publicProducts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicProducts.getHeaders().getFirst("API-Version")).isEqualTo("1");

        var createdProduct = adminExchange("/api/v1/products", HttpMethod.POST,
                new ProductRequest("Versioned product", null, "VER-100"), ProductResponse.class);
        assertThat(createdProduct.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createdProduct.getHeaders().getLocation()).isNotNull();
        assertThat(createdProduct.getHeaders().getLocation().getPath()).startsWith("/api/v1/products/");

        var anonymousAdmin = http.getForEntity("/api/v1/admin/users", String.class);
        assertThat(anonymousAdmin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var registered = http.postForEntity("/api/v1/auth/register", Map.of(
                "email", "versioned-customer@example.test",
                "password", "valid-password-123",
                "firstName", "Versioned",
                "lastName", "Customer"), String.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var login = http.postForEntity("/api/v1/auth/login",
                Map.of("email", "versioned-customer@example.test", "password", "valid-password-123"), LoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.getBody().accessToken());
        var customerAdmin = http.exchange("/api/v1/admin/users", HttpMethod.GET,
                new HttpEntity<>(headers), ApiError.class);
        assertThat(customerAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(customerAdmin.getBody()).isNotNull();
        assertThat(customerAdmin.getBody().status()).isEqualTo(403);
    }

    @Test
    void responseStatusErrorsUseTheVersionedJsonErrorContract() {
        var response = http.postForEntity("/api/v1/auth/register", Map.of(
                "email", "invalid-versioned@example.test",
                "password", "short",
                "firstName", "Invalid",
                "lastName", "Request"), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo("Bad Request");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/auth/register");
        assertThat(response.getHeaders().getFirst("API-Version")).isEqualTo("1");
    }

    @Test
    void generatesOpenApiContractWithCorrectAuthenticationRequirements() throws Exception {
        var response = http.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode contract = mapper.readTree(response.getBody());

        assertThat(contract.at("/info/title").asText()).isEqualTo("Artio Core API");
        assertThat(contract.at("/paths/~1api~1auth~1register").isMissingNode()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1auth~1register/post/security").isMissingNode()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1auth~1mfa~1totp~1enrollment/post/security").isArray()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1products/get").isMissingNode()).isFalse();
        assertThat(contract.at("/paths/~1api~1v1~1payments~1orders~1{orderId}~1checkout-session/post/responses/422").isMissingNode()).isFalse();
        assertThat(contract.at("/paths/~1api~1v1~1payments~1orders~1{orderId}~1checkout-session/post/responses/502").isMissingNode()).isFalse();
        var publicProducts = http.getForEntity("/api/v1/products", String.class);
        assertThat(publicProducts.getHeaders().getFirst("API-Version")).isEqualTo("1");
    }

    private record LoginResponse(String accessToken, String tokenType, long expiresIn, UUID userId, String email, String role,boolean mfaEnrollmentRequired) { }

    @TestConfiguration
    static class LocalCacheConfiguration {
        @Bean
        @Primary
        CacheManager integrationTestCacheManager() {
            return new ConcurrentMapCacheManager("catalog-products", "price-lookups");
        }

        @Bean
        @Primary
        PaymentProvider integrationPaymentProvider(ObjectMapper mapper) {
            var sessions = new java.util.concurrent.ConcurrentHashMap<String, PaymentProvider.CheckoutResult>();
            return new PaymentProvider() {
                @Override public PaymentMethodDiscovery discoverMethods(PaymentDiscoveryRequest request) {
                    if (!"CH".equals(request.country()) || !"CHF".equals(request.currency()))
                        return new PaymentMethodDiscovery(List.of(), List.of());
                    return new PaymentMethodDiscovery(List.of(
                            new AvailablePaymentMethod("integration-test:card", "card", "Card", "integration-test", null, null, 0, true),
                            new AvailablePaymentMethod("integration-test:paypal", "paypal", "PayPal", "integration-test", null, null, 0, true)), List.of());
                }
                @Override public PaymentCapabilities capabilities() {
                    return new PaymentCapabilities(true, false, true, true, true, true, Optional.of(Duration.ofMinutes(31)));
                }
                @Override public CheckoutResult createCheckout(CheckoutRequest request) {
                    RuntimeException failure = PROVIDER_CHECKOUT_FAILURE.getAndSet(null);
                    if (failure != null) throw failure;
                    String identity = request.orderId() + ":" + request.paymentMethodId();
                    return sessions.computeIfAbsent(identity, ignored -> new CheckoutResult("test-" + request.orderId() + "-"
                            + request.paymentMethodId(), "https://payments.example.test/checkout", PaymentState.PENDING));
                }
                @Override public PaymentResult capture(String reference, String key) {
                    return new PaymentResult(reference, PaymentState.CAPTURED, null, null, null);
                }
                @Override public PaymentResult cancel(String reference, String key) {
                    return new PaymentResult(reference, PaymentState.CANCELLED, null, null, null);
                }
                @Override public PaymentResult refund(RefundRequest request) {
                    return new PaymentResult("refund-" + request.idempotencyKey(), PaymentState.REFUNDED,
                            request.amount(), request.currency(), null);
                }
                @Override public WebhookResult verifyWebhook(String signature, byte[] rawBody) {
                    return verifyWebhook(Map.of("provider-signature", signature == null ? "" : signature), rawBody);
                }
                @Override public WebhookResult verifyWebhook(Map<String, String> headers, byte[] rawBody) {
                    PROVIDER_WEBHOOK_HEADERS.set(Map.copyOf(headers));
                    try {
                        JsonNode event = mapper.readTree(rawBody);
                        String type = event.path("type").asText();
                        String state = event.path("data").path("object").path("payment_status").asText();
                        PaymentState paymentState = switch(type){
                            case "checkout.session.expired" -> PaymentState.EXPIRED;
                            case "payment.dispute.opened" -> PaymentState.DISPUTED;
                            case "payment.dispute.won" -> PaymentState.DISPUTE_WON;
                            case "payment.dispute.lost" -> PaymentState.DISPUTE_LOST;
                            case "payment.reversed" -> PaymentState.REVERSED;
                            default -> "paid".equals(state) ? PaymentState.CAPTURED : PaymentState.PENDING;
                        };
                        JsonNode object = event.path("data").path("object");
                        return new WebhookResult(event.path("id").asText(), type, object.path("id").asText(),
                                object.has("transactionReference")?object.path("transactionReference").asText():null,paymentState,
                                new BigDecimal(object.path("amount").asText()), object.path("currency").asText(), true);
                    } catch (RuntimeException invalid) {
                        return new WebhookResult("", "", "", PaymentState.FAILED, false);
                    }
                }
                @Override public Optional<PaymentResult> reconcile(String reference, String key) {
                    try {
                        JsonNode response = mapper.readTree(PROVIDER_RESPONSE.get());
                        String status = response.path("status").asText();
                        String paid = response.path("payment_status").asText();
                        if ("complete".equals(status) && "paid".equals(paid))
                            return Optional.of(new PaymentResult(reference, PaymentState.CAPTURED,
                                    new BigDecimal(response.path("amount").asText()), response.path("currency").asText(), null));
                        if ("expired".equals(status))
                            return Optional.of(new PaymentResult(reference, PaymentState.EXPIRED, null, null, null));
                        return Optional.empty();
                    } catch (RuntimeException invalid) { return Optional.empty(); }
                }
            };
        }
    }

    private <T> ResponseEntity<T> adminExchange(String url, HttpMethod method, Object body, Class<T> responseType,
            Object... uriVariables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> response = http.exchange(url, method, new HttpEntity<>(body, headers), String.class, uriVariables);
        if (response.getBody() == null || response.getBody().isBlank()) {
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).build();
        }
        try {
            T decoded = responseType == String.class ? responseType.cast(response.getBody())
                    : mapper.readValue(response.getBody(), responseType);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(decoded);
        } catch (Exception invalidResponse) {
            throw new org.springframework.web.client.RestClientException("Could not decode " + method + " " + url
                    + " response (HTTP " + response.getStatusCode() + "): " + response.getBody(), invalidResponse);
        }
    }
}
