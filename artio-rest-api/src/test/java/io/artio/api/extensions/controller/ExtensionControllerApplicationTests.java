package io.artio.api.extensions.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(classes = ExtensionControllerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "artio.runtime.role=extension-controller",
                "artio.extensions.docker.enabled=true",
                "artio.extensions.docker.mode=controller",
                "artio.extensions.controller.token=integration-controller-token-value-at-least-32-characters",
                "artio.security.encryption-key=integration-test-encryption-key-value-32-bytes-minimum",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
class ExtensionControllerApplicationTests {
    @MockBean DockerClient docker;
    @Autowired TestRestTemplate http;

    @Test
    void internalLifecycleRoutesRejectCallsWithoutTheControllerCredential() {
        var response = http.postForEntity("/internal/v1/extensions/test-plugin/disable", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void doesNotExposeGraphQlOrOpenApiRoutes() {
        assertThat(http.getForEntity("/graphql", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/v3/api-docs", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
