package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CatalogApiIntegrationTests.LocalCacheConfiguration.class)
class CatalogApiIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private String adminToken;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void authenticateAdminForCatalogAdministration() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,tenant_id,created_at) "
                        + "VALUES (?,?,?,?,?,'ADMIN','00000000-0000-0000-0000-000000000001',now())",
                userId, "admin-" + userId + "@example.test", new BCryptPasswordEncoder().encode("admin-password-123"),
                "Test", "Administrator");
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) "
                        + "VALUES ('00000000-0000-0000-0000-000000000001',?,'ADMIN')", userId);
        var login = http.postForObject("/api/auth/login",
                Map.of("email", "admin-" + userId + "@example.test", "password", "admin-password-123"), LoginResponse.class);
        assertThat(login).isNotNull();
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
    void appliesCurrentApiMigrationsToAnEmptyPostgresDatabase() {
        var appliedVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class);
        assertThat(appliedVersions).contains("6", "7", "8", "9", "10");

        var enterpriseTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name IN "
                        + "('users','tenants','tenant_memberships','mfa_methods','oauth_identities',"
                        + "'audit_logs','consent_records','gift_cards','loyalty_accounts','subscriptions')",
                Integer.class);
        assertThat(enterpriseTables).isEqualTo(10);

        var usersWithoutTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id IS NULL", Long.class);
        assertThat(usersWithoutTenant).isZero();
    }

    @Test
    void requiresAuthenticationForMfaEnrollment() {
        var response = http.postForEntity("/api/v1/auth/mfa/totp/enrollment", Map.of(), ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
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
