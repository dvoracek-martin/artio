package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Read-only client for the configured public Artio Marketplace API. */
@Component
public final class MarketplaceCatalogClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public MarketplaceCatalogClient(ObjectMapper mapper,
            @Value("${artio.marketplace.base-url:}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!this.baseUrl.isEmpty()) {
            URI uri = URI.create(this.baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("artio.marketplace.base-url must be an absolute HTTP(S) URL without credentials, query or fragment");
            }
        }
    }

    public Object plugins(String q, String category, String country, String coreVersion,
            boolean includePrerelease, String locale, int page, int size) {
        Map<String, String> query = new LinkedHashMap<>();
        add(query, "q", q);
        add(query, "category", category);
        add(query, "country", country);
        add(query, "coreVersion", coreVersion);
        query.put("includePrerelease", Boolean.toString(includePrerelease));
        query.put("locale", locale);
        query.put("page", Integer.toString(page));
        query.put("size", Integer.toString(size));
        return get("/api/v1/marketplace/plugins", query);
    }

    public Object releaseManifest(String pluginId, String version, String coreVersion,
            String country, boolean includePrerelease) {
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9._-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pluginId is invalid");
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version must be a semantic version");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("coreVersion", coreVersion);
        add(query, "country", country);
        query.put("includePrerelease", Boolean.toString(includePrerelease));
        return get("/api/v1/marketplace/plugins/" + pathSegment(pluginId) + "/releases/"
                + pathSegment(version) + "/manifest", query);
    }

    private Object get(String path, Map<String, String> query) {
        if (baseUrl.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Marketplace is not configured");
        String encodedQuery = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        URI uri = URI.create(baseUrl + path + (encodedQuery.isEmpty() ? "" : "?" + encodedQuery));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    HttpStatus status = response.statusCode() == 404 ? HttpStatus.NOT_FOUND
                            : response.statusCode() == 410 ? HttpStatus.GONE : HttpStatus.BAD_GATEWAY;
                    throw new ResponseStatusException(status, status == HttpStatus.BAD_GATEWAY
                            ? "Marketplace request failed" : "Marketplace release is unavailable");
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace response exceeded the size limit");
                // Spring Boot 4 serializes with Jackson 3. Return ordinary JSON values here so
                // the Jackson 2 client tree is not exposed as a bean with its internal fields.
                return mapper.readValue(bytes, Object.class);
            }
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Marketplace request was interrupted");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace could not be reached");
        }
    }

    private static void add(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String pathSegment(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
