package io.artio.api.extensions.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import io.artio.extensions.ExtensionRuntimePort;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = ExtensionControllerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "artio.runtime.role=extension-controller",
                "artio.extensions.docker.enabled=true",
                "artio.extensions.docker.mode=controller",
                "artio.extensions.controller.token=integration-controller-token-value-at-least-32-characters",
                "artio.security.extension-auth-key=integration-test-extension-auth-key-value-32-bytes-minimum",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
@AutoConfigureTestRestTemplate
class ExtensionControllerApplicationTests {
    @MockitoBean DockerClient docker;
    @MockitoBean ExtensionRuntimePort runtime;
    @Autowired TestRestTemplate http;

    @Test
    void forwardsAuthenticatedArtioIdentityToTheFixedGrafanaProxy() throws Exception {
        String userId = "fbf38b6f-431a-4f5d-aae7-85ad63f9a0e1";
        String email = "admin@example.invalid";
        when(runtime.proxyObservability("observability.artio", "GET", "/api/user", null, userId, email))
                .thenReturn(new ExtensionRuntimePort.UiProxyResponse(200, "application/json", java.util.Map.of(),
                        "{\"login\":\"artio-fbf38b6f-431a-4f5d-aae7-85ad63f9a0e1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var headers = jsonHeaders();
        headers.setBearerAuth("integration-controller-token-value-at-least-32-characters");
        var response = http.postForEntity("/internal/v1/observability/grafana",
                new HttpEntity<>(new ExtensionObservabilityController.GrafanaProxyRequest(
                        "GET", "/api/user", null, userId, email), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String encodedBody = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody())
                .path("bodyBase64").asText();
        assertThat(new String(java.util.Base64.getDecoder().decode(encodedBody), java.nio.charset.StandardCharsets.UTF_8))
                .contains("artio-fbf38b6f-431a-4f5d-aae7-85ad63f9a0e1");
        verify(runtime).proxyObservability("observability.artio", "GET", "/api/user", null, userId, email);
    }

    @Test
    void internalLifecycleRoutesRejectCallsWithoutTheControllerCredential() {
        var response = http.postForEntity("/internal/v1/extensions/test-plugin/disable", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var bundleHealth = http.postForEntity("/internal/v1/extensions/test-plugin/bundle-health",
                new ManifestHealthRequest(null), String.class);
        assertThat(bundleHealth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/x-protobuf"));
        var telemetry = http.postForEntity("/internal/v1/telemetry/observability.artio/metrics",
                new HttpEntity<>(new byte[] {1}, headers), String.class);
        assertThat(telemetry.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var grafana = http.postForEntity("/internal/v1/observability/grafana",
                new HttpEntity<>("{\"method\":\"GET\",\"path\":\"/\",\"query\":null}", jsonHeaders()), String.class);
        assertThat(grafana.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void doesNotExposeGraphQlOrOpenApiRoutes() {
        assertThat(http.getForEntity("/graphql", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private record ManifestHealthRequest(Object manifest) { }

    private static HttpHeaders jsonHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
