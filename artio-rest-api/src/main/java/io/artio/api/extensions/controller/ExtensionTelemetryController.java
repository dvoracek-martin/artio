package io.artio.api.extensions.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.extensions.ExtensionRuntimePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/** Narrow OTLP bridge from Core to the optional observability plugin on the private extension network. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
@RequestMapping("/internal/v1/telemetry")
final class ExtensionTelemetryController {
    private static final int MAX_PAYLOAD_BYTES = 4_194_304;
    private final ExtensionRuntimePort runtime;
    private final ExtensionControllerSecurity security;

    ExtensionTelemetryController(ExtensionRuntimePort runtime, ExtensionControllerSecurity security) {
        this.runtime = runtime;
        this.security = security;
    }

    @PostMapping(value = "/{pluginId}/{signal}", consumes = "application/x-protobuf")
    ResponseEntity<byte[]> forward(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String pluginId, @PathVariable String signal, HttpServletRequest request) {
        security.authorize(authorization);
        if (!"observability.artio".equals(pluginId) || !java.util.Set.of("metrics", "traces").contains(signal)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OTLP destination is not supported");
        }
        if (request.getContentLengthLong() > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "OTLP payload exceeds its 4 MiB limit");
        }
        byte[] payload;
        try { payload = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1); }
        catch (IOException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTLP payload could not be read"); }
        if (payload.length == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OTLP payload is empty");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "OTLP payload exceeds its 4 MiB limit");
        ExtensionRuntimePort.TelemetryResponse response = runtime.forwardOtlp(pluginId, signal, payload);
        MediaType responseType;
        try { responseType = MediaType.parseMediaType(response.contentType()); }
        catch (RuntimeException invalid) { responseType = MediaType.APPLICATION_JSON; }
        return ResponseEntity.status(response.statusCode()).contentType(responseType).body(response.body());
    }
}
