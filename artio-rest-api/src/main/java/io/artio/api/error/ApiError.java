package io.artio.api.error;

import java.time.Instant;

/** Stable JSON error envelope shared by MVC and Spring Security failures. */
public record ApiError(Instant timestamp, int status, String error, String code, String message, String path) {
    public static String codeFor(int status) {
        return switch (status) {
            case 400, 422 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "SERVER_ERROR" : "REQUEST_FAILED";
        };
    }
}
