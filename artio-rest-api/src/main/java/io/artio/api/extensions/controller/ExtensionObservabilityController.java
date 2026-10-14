package io.artio.api.extensions.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.extensions.ExtensionRuntimePort;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated fixed-destination read-only Grafana proxy for the observability bundle. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
@RequestMapping("/internal/v1/observability")
final class ExtensionObservabilityController {
    private final ExtensionRuntimePort runtime;
    private final ExtensionControllerSecurity security;

    ExtensionObservabilityController(ExtensionRuntimePort runtime, ExtensionControllerSecurity security) {
        this.runtime = runtime;
        this.security = security;
    }

    @PostMapping("/grafana")
    ResponseEntity<GrafanaProxyResponse> grafana(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody GrafanaProxyRequest request) {
        security.authorize(authorization);
        if (request == null || request.path() == null || request.method() == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        ExtensionRuntimePort.UiProxyResponse response = runtime.proxyObservability(
                "observability.artio", request.method(), request.path(), request.query());
        return ResponseEntity.ok(new GrafanaProxyResponse(response.statusCode(), response.contentType(),
                response.headers(), Base64.getEncoder().encodeToString(response.body())));
    }

    record GrafanaProxyRequest(String method, String path, String query) { }
    record GrafanaProxyResponse(int statusCode, String contentType, Map<String, String> headers, String bodyBase64) { }
}
