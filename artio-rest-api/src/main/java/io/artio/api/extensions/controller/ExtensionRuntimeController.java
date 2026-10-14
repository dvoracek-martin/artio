package io.artio.api.extensions.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.extensions.ExtensionRuntimePort;
import io.artio.extensions.PluginManifest;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Narrow authenticated service API. It intentionally exposes no raw Docker operations. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
@RequestMapping("/internal/v1/extensions")
final class ExtensionRuntimeController {
    private final ExtensionRuntimePort runtime;
    private final ExtensionControllerSecurity security;

    ExtensionRuntimeController(ExtensionRuntimePort runtime, ExtensionControllerSecurity security) {
        this.runtime = runtime;
        this.security = security;
    }

    @PostMapping("/install")
    ResponseEntity<Void> install(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ManifestRequest request) {
        security.authorize(authorization);
        runtime.install(request.manifest());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/update")
    ResponseEntity<Void> update(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ManifestRequest request) {
        security.authorize(authorization);
        runtime.update(request.manifest());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/enable")
    ResponseEntity<Void> enable(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody EnableRequest request) {
        security.authorize(authorization);
        runtime.enable(request.manifest(), request.credentialGeneration(), request.configuration());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pluginId}/disable")
    ResponseEntity<Void> disable(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pluginId) {
        security.authorize(authorization);
        runtime.disable(pluginId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pluginId}/uninstall")
    ResponseEntity<Void> uninstall(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pluginId) {
        security.authorize(authorization);
        runtime.uninstall(pluginId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rotate")
    ResponseEntity<Void> rotate(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody RotationRequest request) {
        security.authorize(authorization);
        runtime.rotateCredentials(request.manifest(), request.credentialGeneration(), request.restartIfEnabled(), request.configuration());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{pluginId}/health")
    ExtensionRuntimePort.RuntimeHealth health(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pluginId, @RequestBody HealthRequest request) {
        security.authorize(authorization);
        return runtime.health(pluginId, request.healthPath());
    }

    @PostMapping("/{pluginId}/bundle-health")
    ExtensionRuntimePort.RuntimeHealth bundleHealth(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String pluginId, @RequestBody ManifestHealthRequest request) {
        security.authorize(authorization);
        if (request.manifest() == null || !pluginId.equals(request.manifest().id())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Bundle health manifest identity does not match route");
        }
        return runtime.health(request.manifest());
    }

    @PostMapping("/invoke")
    ExtensionRuntimePort.InvocationResult invoke(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody InvocationRequest request) {
        security.authorize(authorization);
        return runtime.invoke(request.pluginId(), request.credentialGeneration(), request.extensionPoint(), request.operation(),
                request.idempotencyKey(), request.configurationJson(), request.requestJson(), Duration.ofMillis(request.deadlineMillis()));
    }

    record ManifestRequest(PluginManifest manifest, int credentialGeneration) { }
    record EnableRequest(PluginManifest manifest, int credentialGeneration, Map<String, String> configuration) {
        EnableRequest { configuration = configuration == null ? Map.of() : Map.copyOf(configuration); }
    }
    record RotationRequest(PluginManifest manifest, int credentialGeneration, boolean restartIfEnabled,
                           Map<String, String> configuration) {
        RotationRequest { configuration = configuration == null ? Map.of() : Map.copyOf(configuration); }
    }
    record HealthRequest(String healthPath) { }
    record ManifestHealthRequest(PluginManifest manifest) { }
    record InvocationRequest(String pluginId, int credentialGeneration, String extensionPoint, String operation,
            String idempotencyKey, String configurationJson, String requestJson, long deadlineMillis) { }
}
