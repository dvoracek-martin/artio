package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogApiIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void createsAndReadsProductsFromPostgres() {
        var request = new ProductRequest("Studio Monitor", "27 inch display", "MON-2701");
        var created = http.postForEntity("/api/products", request, ProductResponse.class);

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
        var created = http.postForEntity("/api/products",
                new ProductRequest("Studio Display", "Original description", "DSP-2701"), ProductResponse.class);
        assertThat(created.getBody()).isNotNull();

        var updated = http.exchange("/api/products/{id}", HttpMethod.PUT,
                new HttpEntity<>(new UpdateProductRequest("Studio Display X", "Updated")),
                ProductResponse.class, created.getBody().id());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().name()).isEqualTo("Studio Display X");
        assertThat(updated.getBody().sku()).isEqualTo("DSP-2701");

        http.put("/api/products/{id}/active", new SetActiveRequest(false), created.getBody().id());
        var inactive = http.getForEntity("/api/products/{id}", ProductResponse.class, created.getBody().id());
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void persistsAndChangesVariantsUnderTheirProduct() {
        var product = http.postForEntity("/api/products",
                new ProductRequest("Laptop", "Portable computer", "LAP-0001"), ProductResponse.class);
        assertThat(product.getBody()).isNotNull();

        var createdVariant = http.postForEntity("/api/products/{id}/variants",
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
        http.put("/api/variants/{id}/active", new SetActiveRequest(false), variantId);
        var inactive = http.getForEntity("/api/variants/{id}", VariantResponse.class, variantId);
        assertThat(inactive.getBody()).isNotNull();
        assertThat(inactive.getBody().active()).isFalse();
    }

    @Test
    void rejectsInvalidProductsDuplicateSkusAndMissingParents() {
        var invalid = http.postForEntity("/api/products",
                new ProductRequest(" ", null, "MON-2702"), ApiExceptionHandler.ApiError.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        http.postForEntity("/api/products",
                new ProductRequest("Monitor", null, "DUP-100"), ProductResponse.class);
        var duplicate = http.postForEntity("/api/products",
                new ProductRequest("Second monitor", null, "DUP-100"), ApiExceptionHandler.ApiError.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var missingParent = http.postForEntity("/api/products/{id}/variants",
                new VariantRequest("Blue", "ITEM-BLU"), ApiExceptionHandler.ApiError.class, UUID.randomUUID());
        assertThat(missingParent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}