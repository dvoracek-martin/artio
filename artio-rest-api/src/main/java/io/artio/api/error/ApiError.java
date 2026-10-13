package io.artio.api.error;

import java.time.Instant;

/** Stable JSON error envelope shared by MVC and Spring Security failures. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) { }
