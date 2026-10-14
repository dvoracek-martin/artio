package io.artio.api.users;

import io.artio.api.distributions.DistributionPluginProvisioningService;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirstRunSetupIntegrationTests {
    private static final String SETUP_TOKEN = "first-run-integration-token-value-at-least-32";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artio.security.jwt-secret", () -> "integration-test-jwt-secret-value-32-bytes-minimum");
        registry.add("artio.security.encryption-key", () -> "integration-test-encryption-key-value-32-bytes-minimum");
        registry.add("artio.setup.token", () -> SETUP_TOKEN);
        registry.add("artio.extensions.trusted-registries", () -> "ghcr.io");
        registry.add("artio.events.kafka.consumer-enabled", () -> "false");
        registry.add("artio.workers.enabled", () -> "false");
    }

    @Test
    void setupIsResumableProtectedAndCreatesFirstAdminBeforeOpeningCustomerRegistration() {
        var status = http.getForObject("/api/v1/setup/status", FirstRunSetupController.SetupStatus.class);
        assertThat(status).isNotNull();
        assertThat(status.required()).isTrue();
        assertThat(status.complete()).isFalse();

        var signupBeforeSetup = http.postForEntity("/api/v1/auth/register", Map.of(
                "email", "customer@example.test", "password", "customer-password-123", "firstName", "Ada", "lastName", "Lovelace"), String.class);
        assertThat(signupBeforeSetup.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForEntity("/api/v1/products", String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        var invalidToken = postWithToken("/api/v1/setup/progress", "wrong-token", Map.of());
        assertThat(invalidToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM artio_first_run_setup WHERE singleton_id=true", Integer.class)).isEqualTo(1);

        Map<String, String> progress = Map.ofEntries(Map.entry("countryCode", "CH"), Map.entry("currencyCode", "CHF"),
                Map.entry("locale", "de-CH"), Map.entry("timezone", "Europe/Zurich"), Map.entry("email", "owner@example.test"),
                Map.entry("firstName", "Artio"), Map.entry("lastName", "Owner"), Map.entry("storeName", "Artio Test Shop"),
                Map.entry("availableLanguages", "de,fr,it,en"), Map.entry("externalBaseUrl", "https://shop.example.test"));
        var saved = postWithToken("/api/v1/setup/progress", SETUP_TOKEN, progress);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).contains("de-CH").doesNotContain("password");
        assertThat(jdbc.queryForObject("SELECT draft::text FROM artio_first_run_setup WHERE singleton_id=true", String.class))
                .contains("owner@example.test").doesNotContain("password");

        var resumed = postWithToken("/api/v1/setup/progress", SETUP_TOKEN, progress);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        var statusWithToken = postWithToken("/api/v1/setup/resume", SETUP_TOKEN, Map.of());
        assertThat(statusWithToken.getBody()).contains("owner@example.test");

        var weakPassword = postWithToken("/api/v1/setup/complete", SETUP_TOKEN, Map.of("password", "too-short"));
        assertThat(weakPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var completed = postWithToken("/api/v1/setup/complete", SETUP_TOKEN, Map.of("password", "an-extremely-strong-admin-password"));
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).contains("SUPERADMIN", "CH", "CHF", "de-CH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='SUPERADMIN' AND email='owner@example.test'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artio_installation_settings WHERE country_code='CH' AND currency_code='CHF' AND timezone='Europe/Zurich'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT store_name || ':' || distribution_id || ':' || external_base_url FROM artio_installation_settings WHERE singleton_id=true", String.class))
                .isEqualTo("Artio Test Shop:switzerland-v1:https://shop.example.test");
        assertThat(jdbc.queryForObject("SELECT available_languages::text FROM artio_installation_settings WHERE singleton_id=true", String.class)).contains("de", "fr", "it", "en");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artio_distribution_plugin_provisioning WHERE distribution_id='switzerland-v1'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artio_distribution_plugin_provisioning WHERE provisioning_status='WAITING_FOR_RELEASE'", Integer.class)).isEqualTo(3);
        var ownerLogin=http.postForObject("/api/v1/auth/login",Map.of("email","owner@example.test","password","an-extremely-strong-admin-password"),AuthController.LoginResponse.class);
        assertThat(ownerLogin.mfaEnrollmentRequired()).isTrue();
        HttpHeaders ownerHeaders=new HttpHeaders();ownerHeaders.setBearerAuth(ownerLogin.accessToken());
        assertThat(http.exchange("/api/v1/admin/system/distribution/plugins",HttpMethod.GET,new HttpEntity<>(ownerHeaders),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var enrollment=http.exchange("/api/v1/auth/mfa/totp/enrollment",HttpMethod.POST,new HttpEntity<>(Map.of(),ownerHeaders),MfaController.Enrollment.class);
        assertThat(enrollment.getStatusCode()).isEqualTo(HttpStatus.OK);
        String totp=io.artio.api.TestTotp.codeFromBase32(enrollment.getBody().base32Secret(),java.time.Instant.now());
        var verified=http.exchange("/api/v1/auth/mfa/totp/verify",HttpMethod.POST,new HttpEntity<>(new MfaController.CodeRequest(totp),ownerHeaders),Map.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        ownerLogin=http.postForObject("/api/v1/auth/login",Map.of("email","owner@example.test","password","an-extremely-strong-admin-password","mfaCode",io.artio.api.TestTotp.codeFromBase32(enrollment.getBody().base32Secret(),java.time.Instant.now())),AuthController.LoginResponse.class);
        assertThat(ownerLogin.mfaEnrollmentRequired()).isFalse();
        ownerHeaders.setBearerAuth(ownerLogin.accessToken());
        var disabled=http.exchange("/api/v1/auth/mfa/totp",HttpMethod.DELETE,new HttpEntity<>(new MfaController.CodeRequest(io.artio.api.TestTotp.codeFromBase32(enrollment.getBody().base32Secret(),java.time.Instant.now())),ownerHeaders),String.class);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var pluginStatus=http.exchange("/api/v1/admin/system/distribution/plugins",HttpMethod.GET,new HttpEntity<>(ownerHeaders),DistributionPluginProvisioningService.ProvisioningItem[].class);
        assertThat(pluginStatus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pluginStatus.getBody()).hasSize(3).allMatch(item->item.status().equals("WAITING_FOR_RELEASE")&&item.optional());
        var refreshed=http.exchange("/api/v1/admin/system/distribution/plugins/refresh",HttpMethod.POST,new HttpEntity<>(Map.of(),ownerHeaders),DistributionPluginProvisioningService.ProvisioningItem[].class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT draft::text FROM artio_first_run_setup WHERE singleton_id=true", String.class)).isEqualTo("{}");
        assertThat(http.getForObject("/api/v1/setup/status", String.class)).contains("\"complete\":true");
        assertThat(postWithToken("/api/v1/setup/complete", SETUP_TOKEN, Map.of("password", "an-extremely-strong-admin-password")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        var customerRegistration = http.postForEntity("/api/v1/auth/register", Map.of(
                "email", "customer@example.test", "password", "customer-password-123", "firstName", "Ada", "lastName", "Lovelace"), String.class);
        assertThat(customerRegistration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> postWithToken(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Artio-Setup-Token", token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
