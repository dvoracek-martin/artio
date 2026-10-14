package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CatalogApiIntegrationTests.LocalCacheConfiguration.class)
class CatalogApiIntegrationTests {
    private static final String ACTIVE_TEST_ENCRYPTION_KEY = "integration-test-encryption-key-value-32-bytes-minimum";
    private static final String PREVIOUS_TEST_ENCRYPTION_KEY = "previous-test-encryption-key-value-32-bytes-minimum";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final AtomicReference<String> STRIPE_RESPONSE = new AtomicReference<>("{\"status\":\"open\",\"payment_status\":\"unpaid\"}");
    private static final HttpServer MOCK_STRIPE = startMockStripe();

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private io.artio.api.inventory.InventoryApplicationService inventory;

    @Autowired
    private io.artio.api.inventory.ReservationExpiryScheduler reservationExpiryScheduler;

    @Autowired
    private io.artio.api.notifications.OrderEventOutboxPublisher orderEventOutboxPublisher;

    @Autowired
    private io.artio.api.security.EncryptionService encryption;

    @Autowired
    private io.artio.api.payments.CheckoutFailureCompensation checkoutFailureCompensation;

    @Autowired
    private io.artio.api.payments.StripePaymentReconciliationWorker stripePaymentReconciliationWorker;

    private String adminToken;
    private UUID adminUserId;

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
        registry.add("artio.extensions.trusted-registries", () -> "ghcr.io");
        registry.add("artio.inventory.expiry-interval-ms", () -> "3600000");
        registry.add("artio.events.outbox-poll-interval-ms", () -> "3600000");
        registry.add("artio.events.kafka.enabled", () -> "true");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.workers.enabled", () -> "false");
        registry.add("artio.payments.stripe-secret-key", () -> "sk_test_integration_only");
        registry.add("artio.payments.stripe-webhook-secret", () -> "whsec_integration_test_secret");
        registry.add("artio.payments.reconciliation-api-url", () -> "http://127.0.0.1:" + MOCK_STRIPE.getAddress().getPort());
    }

    private static HttpServer startMockStripe() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/checkout/sessions/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] response = STRIPE_RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void stopMockStripe() {
        MOCK_STRIPE.stop(0);
    }

    @BeforeEach
    void authenticateAdminForCatalogAdministration() {
        UUID userId = UUID.randomUUID();
        adminUserId = userId;
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) "
                        + "VALUES (?,?,?,?,?,'ADMIN',now())",
                userId, "admin-" + userId + "@example.test", new BCryptPasswordEncoder().encode("admin-password-123"),
                "Test", "Administrator");
        var login = http.postForObject("/api/auth/login",
                Map.of("email", "admin-" + userId + "@example.test", "password", "admin-password-123"), LoginResponse.class);
        assertThat(login).isNotNull();
        assertThat(login.accessToken()).isNotBlank();
        adminToken = login.accessToken();
    }

    @Test
    void createsAndReadsProductsFromPostgres() {
        var request = new ProductRequest("Studio Monitor", "27 inch display", "MON-2701");
        var created = adminExchange("/api/products", HttpMethod.POST, request, ProductResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().id()).isNotNull();
        assertThat(created.getBody().active()).isTrue();

        var read = http.getForEntity("/api/products/{id}", ProductResponse.class, created.getBody().id());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody()).isNotNull();
        assertThat(read.getBody().sku()).isEqualTo("MON-2701");
    }

    @Test
    void duplicatesProductAsUnpublishedDraftWithFreshIdentityAndSku(){
        var source=adminExchange("/api/v1/products",HttpMethod.POST,
                new io.artio.api.catalog.ProductRequest("Desk lamp","Brass lamp","LAMP-ORIGINAL"),
                io.artio.api.catalog.ProductResponse.class);
        assertThat(source.getBody()).isNotNull();
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
                new io.artio.api.catalog.MetadataApplicationService.AttributeRequest("MATERIAL","Material",
                        io.artio.catalog.AttributeDefinition.Type.TEXT,false),
                io.artio.api.catalog.MetadataApplicationService.AttributeView.class);
        assertThat(attribute.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var product=adminExchange("/api/v1/products",HttpMethod.POST,new ProductRequest("Table lamp","Brass finish","LMP-META"),ProductResponse.class);
        var assigned=adminExchange("/api/v1/products/{id}/catalog-metadata",HttpMethod.PUT,
                new io.artio.api.catalog.ProductApplicationService.CatalogMetadataRequest(brand.getBody().id(),
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of("MATERIAL","brass")),ProductResponse.class,product.getBody().id());
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().brandId()).isEqualTo(brand.getBody().id());
        assertThat(assigned.getBody().categoryIds()).contains(root.getBody().id(),child.getBody().id());
        assertThat(assigned.getBody().attributes()).containsEntry("MATERIAL","brass");

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
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of("MATERIAL","brass")),ProductResponse.class,product.getBody().id());
        assertThat(updatedWithExistingInactiveMetadata.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updatedWithExistingInactiveMetadata.getBody().categoryIds()).contains(child.getBody().id());
        var removedInactiveValue=adminExchange("/api/v1/products/{id}/catalog-metadata",HttpMethod.PUT,
                new io.artio.api.catalog.ProductApplicationService.CatalogMetadataRequest(brand.getBody().id(),
                        Set.of(root.getBody().id(),child.getBody().id()),Map.of()),ApiError.class,product.getBody().id());
        assertThat(removedInactiveValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatesProductDescriptionAndActivationStateWithoutChangingItsSku() {
        var created = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Studio Display", "Original description", "DSP-2701"), ProductResponse.class);
        assertThat(created.getBody()).isNotNull();

        var updated = adminExchange("/api/products/{id}", HttpMethod.PUT,
                new UpdateProductRequest("Studio Display X", "Updated"), ProductResponse.class, created.getBody().id());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().name()).isEqualTo("Studio Display X");
        assertThat(updated.getBody().sku()).isEqualTo("DSP-2701");

        adminExchange("/api/products/{id}/active", HttpMethod.PUT,
                new SetActiveRequest(false), String.class, created.getBody().id());
        var inactive = http.getForEntity("/api/products/{id}", ProductResponse.class, created.getBody().id());
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void storesTranslationsIndependentlyAndResolvesLocaleFallbackPerField() {
        var product = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Coffee", "Base description", "COF-100"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();
        UUID id = product.getBody().id();

        adminExchange("/api/products/{id}/translations/{locale}", HttpMethod.PUT,
                new ProductTranslationApplicationService.TranslationRequest("Kaffee", null),
                ProductTranslationApplicationService.TranslationView.class, id, "de");
        adminExchange("/api/products/{id}/translations/{locale}", HttpMethod.PUT,
                new ProductTranslationApplicationService.TranslationRequest("Coffee", "Roasted beans"),
                ProductTranslationApplicationService.TranslationView.class, id, "en");

        var localized = http.getForEntity("/api/v1/products/{id}/localized?locale=de",
                ProductTranslationApplicationService.LocalizedProductView.class, id);
        assertThat(localized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(localized.getBody()).isNotNull();
        assertThat(localized.getBody().name()).isEqualTo("Kaffee");
        assertThat(localized.getBody().description()).isEqualTo("Roasted beans");
        assertThat(localized.getBody().sku()).isEqualTo("COF-100");

        var translations = http.getForEntity("/api/products/{id}/translations",
                ProductTranslationApplicationService.TranslationView[].class, id);
        assertThat(translations.getBody()).hasSize(2);
        var canonical = http.getForEntity("/api/products/{id}", ProductResponse.class, id);
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
        assertThat(validation.getBody().valid()).isTrue();

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
        assertThat(adminExchange("/api/v1/admin/extensions", HttpMethod.GET, null, Map[].class).getBody())
                .noneMatch(plugin -> manifest.id().equals(plugin.get("id")));
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

        var invalid = adminExchange("/api/v1/admin/extensions?country=XX", HttpMethod.GET, null, ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void installationSettingsAreRestrictedToSuperadminAndAudited() {
        var denied = adminExchange("/api/v1/admin/system/settings", HttpMethod.GET, null, ApiError.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID ownerId = UUID.randomUUID();
        String ownerEmail = "owner-" + ownerId + "@example.test";
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'SUPERADMIN',now())",
                ownerId, ownerEmail, new BCryptPasswordEncoder().encode("owner-password-123"), "System", "Owner");
        LoginResponse ownerLogin = http.postForObject("/api/auth/login", Map.of("email", ownerEmail, "password", "owner-password-123"), LoginResponse.class);
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
        var ownerLogin = http.postForObject("/api/auth/login",
                Map.of("email", "admin-" + adminUserId + "@example.test", "password", "admin-password-123"), LoginResponse.class);
        assertThat(ownerLogin).isNotNull();
        adminToken = ownerLogin.accessToken();

        var previousEncryption = new io.artio.api.security.EncryptionService(PREVIOUS_TEST_ENCRYPTION_KEY);
        String oldMfaCiphertext = previousEncryption.encrypt("mfa-secret-material");
        String oldPluginCiphertext = previousEncryption.encrypt("provider-secret-material");
        jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret) VALUES (?,?)", adminUserId, oldMfaCiphertext);
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
        String newMfaCiphertext = jdbc.queryForObject("SELECT encrypted_secret FROM mfa_methods WHERE user_id=?", String.class, adminUserId);
        String newPluginCiphertext = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugins WHERE plugin_id='test.key-rotation'", String.class);
        assertThat(encryption.isEncryptedWithActiveKey(newMfaCiphertext)).isTrue();
        assertThat(encryption.isEncryptedWithActiveKey(newPluginCiphertext)).isTrue();
        assertThat(encryption.decrypt(newMfaCiphertext)).isEqualTo("mfa-secret-material");
        assertThat(encryption.decrypt(newPluginCiphertext)).isEqualTo("provider-secret-material");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE path=? AND action='UPDATE'", Integer.class, path)).isEqualTo(1);
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
        adminExchange("/api/v1/admin/extensions/{id}/configuration", HttpMethod.PUT, Map.of(),
                io.artio.api.extensions.ExtensionManagementController.PluginView.class, dependent.id());

        var install = adminExchange("/api/v1/admin/extensions/{id}/install", HttpMethod.POST, null, Map.class, dependent.id());
        assertThat(install.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
        var warehouse = adminExchange("/api/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("EXP-01", "Expiry warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getBody()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
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
                        + "VALUES (?,?,'PENDING',1,0,0,1,'CHF','address','address',now(),'CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "compensation-" + orderId);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'CHF','STRIPE','PENDING',?,now(),now())",
                UUID.randomUUID(), orderId, adminUserId, "cs_compensation_" + orderId);

        checkoutFailureCompensation.cancelRejectedCheckout(orderId);

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId)).isEqualTo("FAILED");
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
    void expiryJobCancelsUnpaidOrdersOnlyAfterEveryReservationHasExpired() {
        var warehouse = adminExchange("/api/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "CANCEL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Cancellation warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(BigDecimal.ONE),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseId, sellableId);

        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID unpaidOrderId = insertPendingOrder(userId);
        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                unpaidOrderId, warehouseId, sellableId, BigDecimal.ONE));
        jdbc.update("UPDATE inventory_reservation SET expires_at=now()-interval '1 second' WHERE id=?", reservation.id());
        UUID paymentId = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','STRIPE','PENDING',?,now(),now())",
                paymentId, unpaidOrderId, userId, "cs_expired_" + paymentId);

        reservationExpiryScheduler.releaseExpiredReservations();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, unpaidOrderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("FAILED");
        assertThat(inventory.reservationsByOrder(unpaidOrderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
    }

    @Test
    void signedStripeExpiryWebhookCancelsOrderAndFailsItsPayment() throws Exception {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email LIKE 'admin-%@example.test' ORDER BY created_at DESC LIMIT 1", UUID.class);
        UUID orderId = insertPendingOrder(userId);
        UUID paymentId = UUID.randomUUID();
        String sessionId = "cs_expired_" + paymentId;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','STRIPE','PENDING',?,now(),now())",
                paymentId, orderId, userId, sessionId);
        UUID secondPaymentId = UUID.randomUUID();
        String secondSessionId = "cs_second_" + secondPaymentId;
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,1,'USD','STRIPE','PENDING',?,now(),now())",
                secondPaymentId, orderId, userId, secondSessionId);

        var expired = signedStripeWebhook("checkout.session.expired", sessionId, "unpaid");
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("FAILED");
        var duplicateExpiry = signedStripeWebhook("checkout.session.expired", sessionId, "unpaid");
        assertThat(duplicateExpiry.getBody()).contains("duplicate");
        String expiryEventId = "evt_test_" + UUID.nameUUIDFromBytes(("checkout.session.expired:" + sessionId).getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_webhook_receipts WHERE provider='STRIPE' AND event_id=?", Integer.class, expiryEventId)).isEqualTo(1);

        var lateSuccess = signedStripeWebhook("checkout.session.completed", sessionId, "paid");
        assertThat(lateSuccess.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PENDING");

        signedStripeWebhook("checkout.session.expired", secondSessionId, "unpaid");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId)).isEqualTo("FAILED");
    }

    @Test
    void retainsOrderOutboxEventAndSchedulesRetryWhenKafkaIsUnavailable() {
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO event_outbox(id,event_type,aggregate_type,aggregate_id,payload) "
                        + "VALUES (?,'ORDER_CREATED','ORDER',?,'{}'::jsonb)", eventId, UUID.randomUUID().toString());

        orderEventOutboxPublisher.publishPending();

        assertThat(jdbc.queryForObject("SELECT status FROM event_outbox WHERE id=?", String.class, eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM event_outbox WHERE id=?", Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_at>now() FROM event_outbox WHERE id=?", Boolean.class, eventId)).isTrue();
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

    private ResponseEntity<String> signedStripeWebhook(String type, String sessionId, String paymentStatus) throws Exception {
        String eventId = "evt_test_" + UUID.nameUUIDFromBytes((type + ":" + sessionId).getBytes(StandardCharsets.UTF_8));
        String body = "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + sessionId
                + "\",\"payment_status\":\"" + paymentStatus + "\"}}}";
        String timestamp = Long.toString(java.time.Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec_integration_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "t=" + timestamp + ",v1=" + signature);
        return http.exchange("/api/v1/payments/stripe/webhook", HttpMethod.POST,
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
        var warehouse = adminExchange("/api/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "CONC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Concurrency warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(warehouse.getBody()).isNotNull();
        assertThat(warehouse.getBody().id()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
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

        var adjusted=adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}",HttpMethod.PUT,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("6"),"Cycle count correction"),
                io.artio.api.inventory.InventoryApplicationService.StockView.class,warehouseId,sellableId);
        assertThat(adjusted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adjusted.getBody()).isNotNull();
        assertThat(adjusted.getBody().onHand()).isEqualByComparingTo("6");
        var movements=adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/movements?limit=20",HttpMethod.GET,null,
                io.artio.inventory.StockMovement[].class,warehouseId,sellableId);
        assertThat(movements.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(movements.getBody()).extracting("type").contains("RECEIPT","RESERVATION","ADJUSTMENT");
        assertThat(movements.getBody()).filteredOn(movement->movement.type().equals("ADJUSTMENT"))
                .singleElement().satisfies(movement->{assertThat(movement.reason()).isEqualTo("Cycle count correction");assertThat(movement.actorId()).isEqualTo(adminUserId);});
    }

    @Test
    void extendsReservedStockThroughThePaymentSessionWindow() {
        var warehouse = adminExchange("/api/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                        "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Payment warehouse", "CH-ZH"),
                io.artio.api.inventory.InventoryApplicationService.WarehouseView.class);
        assertThat(warehouse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(warehouse.getBody()).isNotNull();
        assertThat(warehouse.getBody().id()).isNotNull();
        UUID warehouseId = warehouse.getBody().id();
        UUID sellableId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
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

        var standard = http.postForEntity("/api/pricing/quote",
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
        var reduced = http.postForEntity("/api/pricing/quote",
                new io.artio.api.pricing.PricingApplicationService.QuoteRequest(variant.getBody().id(), "CH", "WEB", "CH", BigDecimal.ONE, null),
                String.class);
        assertThat(reduced.getStatusCode()).withFailMessage("Reduced Swiss pricing response: %s",reduced.getBody()).isEqualTo(HttpStatus.OK);
        var reducedQuote=mapper.readValue(reduced.getBody(),io.artio.pricing.PricingQuote.class);
        assertThat(reducedQuote.taxCategory()).isEqualTo(io.artio.pricing.TaxCategory.REDUCED);
        assertThat(reducedQuote.tax().amount()).isEqualByComparingTo("2.60");
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
        var product = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Tax snapshot item", "Taxable", "TAX-SNAP-1"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();
        var variant = adminExchange("/api/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Standard", "TAX-SNAP-1-STD"), VariantResponse.class, product.getBody().id());
        assertThat(variant.getBody()).isNotNull();
        adminExchange("/api/prices", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.PriceRequest(variant.getBody().id(), "US", "WEB",
                        new BigDecimal("50.00"), "USD", java.time.Instant.now().minusSeconds(3600), null),
                io.artio.api.pricing.PricingApplicationService.PriceView.class);
        adminExchange("/api/tax-rules", HttpMethod.POST,
                new io.artio.api.pricing.PricingApplicationService.TaxRequest("US", new BigDecimal("8.25"),
                        java.time.Instant.now().minusSeconds(3600), null),
                io.artio.api.pricing.PricingApplicationService.TaxView.class);
        var warehouse = adminExchange("/api/warehouses", HttpMethod.POST,
                new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest("IDEM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Checkout", "US"),
                String.class);
        assertThat(warehouse.getStatusCode()).withFailMessage("Warehouse response: %s", warehouse.getBody()).isEqualTo(HttpStatus.CREATED);
        var warehouseView = readWarehouse(warehouse.getBody());
        var received = adminExchange("/api/warehouses/{warehouseId}/stock/{sellableId}/receipts", HttpMethod.POST,
                new io.artio.api.inventory.InventoryController.QuantityRequest(new BigDecimal("10")),
                io.artio.api.inventory.InventoryApplicationService.StockView.class, warehouseView.id(), variant.getBody().id());
        assertThat(received.getStatusCode()).withFailMessage("Receipt response: %s", received.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(received.getBody()).isNotNull();
        assertThat(received.getBody().available()).isEqualByComparingTo("10");
        var bundleResponse = adminExchange("/api/bundles", HttpMethod.POST,
                new io.artio.api.bundles.BundleApplicationService.BundleRequest("Snapshot bundle", "SNAP-BUNDLE-1",
                        io.artio.bundles.BundleType.FIXED,
                        List.of(new io.artio.api.bundles.BundleApplicationService.ComponentRequest(variant.getBody().id(), BigDecimal.ONE)),
                        List.of(), new io.artio.api.bundles.BundleApplicationService.PricingRuleRequest(
                                io.artio.bundles.BundlePricingRule.Mode.COMPONENT_SUM, null, null, null)),
                io.artio.api.bundles.BundleApplicationService.BundleView.class);
        assertThat(bundleResponse.getBody()).isNotNull();
        adminExchange("/api/bundles/{id}/active", HttpMethod.PUT,
                new io.artio.api.bundles.BundleController.ActiveRequest(true),
                io.artio.api.bundles.BundleApplicationService.BundleView.class, bundleResponse.getBody().id());
        adminExchange("/api/cart/bundles", HttpMethod.POST,
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
        assertThat(eventPayload).contains("\"locale\":\"de\"");
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
        var created = adminExchange("/api/bundles", HttpMethod.POST,
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
        var saved = adminExchange("/api/bundles/{id}/translations/{locale}", HttpMethod.PUT,
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
        adminExchange("/api/bundles/{id}/translations/{locale}", HttpMethod.PUT,
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
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('STRIPE',?,'completed',now()-interval '120 days',now()-interval '5 days')", eventPrefix + "-recently-processed");
        jdbc.update("INSERT INTO payment_webhook_receipts(provider,event_id,event_type,received_at,processed_at) VALUES ('STRIPE',?,'completed',now()-interval '1 day',now()-interval '120 days')", eventPrefix + "-old-processed");
        var anonymous = http.getForEntity("/api/v1/admin/operations/outbox",
                io.artio.api.notifications.OutboxOperationsController.OutboxSummary.class);
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
    void reconciliationMarksLostPaidWebhookAsCapturedAndPaid() {
        UUID orderId = createStaleStripeOrder("cs_reconcile_paid_" + UUID.randomUUID());
        STRIPE_RESPONSE.set("{\"status\":\"complete\",\"payment_status\":\"paid\"}");

        stripePaymentReconciliationWorker.reconcileDueSessions();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId)).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT reconciliation_claimed_at IS NULL FROM payments WHERE order_id=?", Boolean.class, orderId)).isTrue();
    }

    @Test
    void reconciliationExpiresLostSessionAndReleasesReservations() {
        UUID orderId = createStaleStripeOrder("cs_reconcile_expired_" + UUID.randomUUID());
        UUID sellableId = UUID.randomUUID();
        var warehouse = inventory.createWarehouse(new io.artio.api.inventory.InventoryApplicationService.WarehouseRequest(
                "RECON-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT), "Reconciliation", "CH-ZH"));
        inventory.receive(warehouse.id(), sellableId, BigDecimal.ONE);
        var reservation = inventory.reserve(new io.artio.api.inventory.InventoryApplicationService.ReserveRequest(
                orderId, warehouse.id(), sellableId, BigDecimal.ONE));
        STRIPE_RESPONSE.set("{\"status\":\"expired\",\"payment_status\":\"unpaid\"}");

        stripePaymentReconciliationWorker.reconcileDueSessions();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id=?", String.class, orderId)).isEqualTo("FAILED");
        assertThat(inventory.reservationsByOrder(orderId)).singleElement()
                .extracting(io.artio.api.inventory.InventoryApplicationService.ReservationView::state)
                .isEqualTo(io.artio.inventory.ReservationState.RELEASED);
    }

    private UUID createStaleStripeOrder(String sessionId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders(id,user_id,status,subtotal,tax,shipping,total,currency,shipping_address,billing_address,created_at,shipping_country,market,tax_jurisdiction,tax_snapshot,idempotency_key) "
                        + "VALUES (?,?,'PENDING',12.50,0,0,12.50,'CHF','address','address',now()-interval '45 minutes','CH','CH','CH','{}'::jsonb,?)",
                orderId, adminUserId, "reconciliation-" + orderId);
        jdbc.update("INSERT INTO payments(id,order_id,user_id,amount,currency,method,status,provider_reference,created_at,updated_at) "
                        + "VALUES (?,?,?,12.50,'CHF','STRIPE','PENDING',?,now()-interval '45 minutes',now())",
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
        var product = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Laptop", "Portable computer", "LAP-0001"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();

        var createdVariant = adminExchange("/api/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("16 GB / 512 GB", "LAP-0001-16-512"), VariantResponse.class,
                product.getBody().id());

        assertThat(createdVariant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createdVariant.getBody()).isNotNull();
        assertThat(createdVariant.getBody().productId()).isEqualTo(product.getBody().id());

        ResponseEntity<VariantResponse[]> variants = http.getForEntity(
                "/api/products/{id}/variants", VariantResponse[].class, product.getBody().id());
        assertThat(variants.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(variants.getBody()).hasSize(1);
        assertThat(variants.getBody()[0].sku()).isEqualTo("LAP-0001-16-512");

        var variantId = createdVariant.getBody().id();
        var anonymousStatusChange=http.exchange("/api/variants/{id}/active",HttpMethod.PUT,
                new HttpEntity<>(new SetActiveRequest(false)),VariantResponse.class,variantId);
        assertThat(anonymousStatusChange.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        adminExchange("/api/variants/{id}/active", HttpMethod.PUT, new SetActiveRequest(false), String.class, variantId);
        var inactive = http.getForEntity("/api/variants/{id}", VariantResponse.class, variantId);
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void rejectsInvalidProductsDuplicateSkusAndMissingParents() {
        var invalid = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest(" ", null, "MON-2702"), ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Monitor", null, "DUP-100"), ProductResponse.class);
        var duplicate = adminExchange("/api/products", HttpMethod.POST,
                new ProductRequest("Second monitor", null, "DUP-100"), ApiError.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var missingParent = adminExchange("/api/products/{id}/variants", HttpMethod.POST,
                new VariantRequest("Blue", "ITEM-BLU"), ApiError.class, UUID.randomUUID());
        assertThat(missingParent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void appliesCurrentApiMigrationsToAnEmptyPostgresDatabaseWithoutTenantSchema() {
        var appliedVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class);
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
        String base="/api/v1/admin/users";
        var recent=adminExchange(base+"?q="+"admin-"+adminUserId+"&activity=RECENT",HttpMethod.GET,null,
                io.artio.api.users.UserController.UserView[].class);
        assertThat(recent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recent.getBody()).hasSize(1);
        assertThat(recent.getBody()[0].id()).isEqualTo(adminUserId);
        assertThat(recent.getBody()[0].lastLogin()).isNotNull();

        var never=adminExchange(base+"?activity=NEVER",HttpMethod.GET,null,io.artio.api.users.UserController.UserView[].class);
        assertThat(never.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(never.getBody()).extracting("id").doesNotContain(adminUserId);
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
        assertThat(contract.at("/paths/~1api~1auth~1register/post/security").isMissingNode()).isTrue();
        assertThat(contract.at("/paths/~1api~1auth~1mfa~1totp~1enrollment/post/security").isArray()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1auth~1register/post/security").isMissingNode()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1auth~1mfa~1totp~1enrollment/post/security").isArray()).isTrue();
        assertThat(contract.at("/paths/~1api~1v1~1products/get").isMissingNode()).isFalse();
    }

    private record LoginResponse(String accessToken, String tokenType, long expiresIn, UUID userId, String email, String role) { }

    @TestConfiguration
    static class LocalCacheConfiguration {
        @Bean
        @Primary
        CacheManager integrationTestCacheManager() {
            return new ConcurrentMapCacheManager("catalog-products", "price-lookups");
        }
    }

    private <T> ResponseEntity<T> adminExchange(String url, HttpMethod method, Object body, Class<T> responseType,
            Object... uriVariables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        return http.exchange(url, method, new HttpEntity<>(body, headers), responseType, uriVariables);
    }
}
