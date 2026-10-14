package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import jakarta.servlet.http.HttpServletRequest;
import io.artio.api.error.ApiError;
import io.artio.api.security.ApiVersioningFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(io.artio.api.licensing.FeatureNotLicensedException.class)
    ResponseEntity<ApiError> notLicensed(io.artio.api.licensing.FeatureNotLicensedException exception, HttpServletRequest request) {
        Object originalPath = request.getAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE);
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(java.time.Instant.now(), 403,
                "Forbidden", "FEATURE_NOT_LICENSED", exception.getMessage(), path));
    }

    @ExceptionHandler(CatalogNotFoundException.class)
    ResponseEntity<ApiError> notFound(CatalogNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict(IllegalStateException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> duplicate(DataIntegrityViolationException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "The request conflicts with an existing unique value", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason();
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String fallback = knownStatus == null ? "HTTP Error" : knownStatus.getReasonPhrase();
        return error(status, message == null || message.isBlank() ? fallback : message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidRequest(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Request validation failed");
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableRequest(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or malformed", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API failure for {} ({})", request.getRequestURI(), exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred", request);
    }

    private ResponseEntity<ApiError> error(HttpStatusCode status, String message, HttpServletRequest request) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String reason = knownStatus == null ? "HTTP Error" : knownStatus.getReasonPhrase();
        Object originalPath = request.getAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE);
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        return ResponseEntity.status(status).body(new ApiError(java.time.Instant.now(), status.value(),
                reason, ApiError.codeFor(status.value()), message, path));
    }

}
