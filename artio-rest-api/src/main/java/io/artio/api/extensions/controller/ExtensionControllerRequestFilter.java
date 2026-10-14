package io.artio.api.extensions.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes before MVC reads metadata and bounds both fixed-length and chunked controller envelopes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
final class ExtensionControllerRequestFilter extends OncePerRequestFilter {
    // Covers two escaped 1 MiB RPC JSON strings, configuration binding overhead and the 4 MiB OTLP body.
    static final int MAX_ENVELOPE_BYTES = 8 * 1024 * 1024;
    private final ExtensionControllerSecurity security;
    private final ObjectMapper mapper;

    ExtensionControllerRequestFilter(ExtensionControllerSecurity security, ObjectMapper mapper) {
        this.security = security;
        this.mapper = mapper;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/internal/v1/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try { security.authorize(request.getHeader("Authorization")); }
        catch (ResponseStatusException unauthorized) {
            reject(response, 401, "INVALID_CONTROLLER_CREDENTIAL");
            return;
        }
        if (request.getContentLengthLong() > MAX_ENVELOPE_BYTES) {
            reject(response, 413, "CONTROLLER_PAYLOAD_TOO_LARGE");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_ENVELOPE_BYTES + 1);
        if (body.length > MAX_ENVELOPE_BYTES) {
            reject(response, 413, "CONTROLLER_PAYLOAD_TOO_LARGE");
            return;
        }
        var input = new BufferedBodyStream(body);
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public BufferedReader getReader() {
                String encoding = getCharacterEncoding();
                return new BufferedReader(new InputStreamReader(input,
                        encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
            }
        }, response);
    }

    private void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), Map.of("code", code));
        response.flushBuffer();
    }

    private static final class BufferedBodyStream extends ServletInputStream {
        private final ByteArrayInputStream input;
        BufferedBodyStream(byte[] bytes) { this.input = new ByteArrayInputStream(bytes); }
        @Override public int read() { return input.read(); }
        @Override public int read(byte[] bytes, int offset, int length) { return input.read(bytes, offset, length); }
        @Override public boolean isFinished() { return input.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) {
            try {
                if (!isFinished()) listener.onDataAvailable();
                if (isFinished()) listener.onAllDataRead();
            } catch (IOException failure) { listener.onError(failure); }
        }
    }
}
