package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("dev")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "artio.workers.enabled=false", "artio.events.kafka.enabled=false", "artio.rate-limit.enabled=false",
        "artio.security.jwt-secret=product-draft-test-jwt-secret-32-characters-minimum",
        "artio.security.encryption-key=product-draft-test-encryption-key-32-characters-minimum",
        "artio.setup.token=product-draft-test-setup-token-32-characters-minimum"
})
@AutoConfigureTestRestTemplate
class ProductDraftIntegrationTests {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test void createsInactiveClassifiedProductAndVariantAndRollsBackOnFailure() {
        HttpHeaders headers = staffHeaders();
        var category = http.exchange("/api/v1/categories", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Studio", "active", true), headers), Map.class).getBody();
        UUID categoryId = UUID.fromString(category.get("id").toString());
        String suffix = UUID.randomUUID().toString().substring(0,8).toUpperCase();
        var request = new ProductDraftController.DraftRequest("Studio lamp", "Warm light", "LAMP-"+suffix,
                "Standard", "LAMP-"+suffix+"-STD", null, Set.of(categoryId), Map.of());
        assertThat(http.postForEntity("/api/v1/products/drafts", request, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var response = http.exchange("/api/v1/products/drafts", HttpMethod.POST, new HttpEntity<>(request, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().active()).isFalse();
        assertThat(response.getBody().categoryIds()).containsExactly(categoryId);
        var variants = http.getForObject("/api/v1/products/"+response.getBody().id()+"/variants", VariantResponse[].class);
        assertThat(variants).hasSize(1);
        assertThat(variants[0].sku()).isEqualTo(request.variantSku());

        // A duplicate variant SKU fails after the product insert; the entire draft must roll back.
        String failingSku = "FAILED-"+suffix;
        var failure = new ProductDraftController.DraftRequest("Failed draft", "", failingSku,
                "Standard", request.variantSku(), null, Set.of(categoryId), Map.of());
        assertThat(http.exchange("/api/v1/products/drafts", HttpMethod.POST, new HttpEntity<>(failure,headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_product WHERE sku=?", Integer.class, failingSku)).isZero();

        var missingCategory = new ProductDraftController.DraftRequest("No category", "", "NOCAT-"+suffix,
                "Standard", "NOCAT-"+suffix+"-STD", null, Set.of(), Map.of());
        assertThat(http.exchange("/api/v1/products/drafts", HttpMethod.POST, new HttpEntity<>(missingCategory, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpHeaders staffHeaders() {
        UUID id = UUID.randomUUID();
        String email = "draft-"+id+"@example.test";
        jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,?,?,?,'ADMIN',now())",
                id, email, new BCryptPasswordEncoder().encode("ProductDraftTest!2026"), "Product", "Tester");
        Map<?,?> login = http.postForObject("/api/v1/auth/login", Map.of("email",email,"password","ProductDraftTest!2026"), Map.class);
        var headers = new HttpHeaders();
        headers.setBearerAuth(login.get("accessToken").toString());
        return headers;
    }
}
