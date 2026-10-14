package io.artio.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.ApiVersioningFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/** Writes the public error contract before MVC handling, including authentication and authorization failures. */
public final class ApiErrorWriter {
    private ApiErrorWriter() { }

    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String message) throws IOException {
        Object originalPath = request.getAttribute(ApiVersioningFilter.ORIGINAL_REQUEST_PATH_ATTRIBUTE);
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        response.setStatus(status.value());
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), new ApiError(
                Instant.now(), status.value(), status.getReasonPhrase(), ApiError.codeFor(status.value()), message, path));
    }
}
