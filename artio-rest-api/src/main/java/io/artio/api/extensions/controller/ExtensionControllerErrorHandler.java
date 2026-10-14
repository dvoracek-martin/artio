package io.artio.api.extensions.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import io.artio.extensions.ExtensionRuntimePort;
import java.util.Map;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
final class ExtensionControllerErrorHandler {
    @ExceptionHandler(ExtensionRuntimePort.ExtensionCallException.class)
    ResponseEntity<Map<String, String>> providerFailure(ExtensionRuntimePort.ExtensionCallException failure) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .header("X-Artio-Extension-Status", Integer.toString(failure.statusCode()))
                .body(Map.of("code", "EXTENSION_CALL_FAILED"));
    }

    @ExceptionHandler(ExtensionRuntimePort.ExtensionTimeoutException.class)
    ResponseEntity<Map<String, String>> timeout() {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("code", "EXTENSION_TIMEOUT"));
    }

    @ExceptionHandler(ExtensionRuntimePort.ExtensionUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "EXTENSION_UNAVAILABLE"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_EXTENSION_REQUEST"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> invalidState() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "INVALID_EXTENSION_STATE"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadableRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_EXTENSION_REQUEST"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> requestStatus(ResponseStatusException failure) {
        int status = failure.getStatusCode().value();
        return ResponseEntity.status(failure.getStatusCode()).body(Map.of("code", status == 401
                ? "INVALID_CONTROLLER_CREDENTIAL" : "CONTROLLER_REQUEST_REJECTED"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "NOT_FOUND"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> operationFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "CONTROLLER_OPERATION_FAILED"));
    }
}
