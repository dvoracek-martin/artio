package io.artio.api.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.extensions.ReleaseDescriptorCanonicalizer;
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
    private final OfficialReleaseDescriptorVerifier descriptorVerifier;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @org.springframework.beans.factory.annotation.Autowired
    public MarketplaceCatalogClient(ObjectMapper mapper,
            @Value("${artio.marketplace.base-url:}") String baseUrl,
            OfficialReleaseDescriptorVerifier descriptorVerifier) {
        this.mapper = mapper;
        this.descriptorVerifier = descriptorVerifier;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!this.baseUrl.isEmpty()) {
            URI uri = URI.create(this.baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("artio.marketplace.base-url must be an absolute HTTP(S) URL without credentials, query or fragment");
            }
        }
    }

    public MarketplaceCatalogClient(ObjectMapper mapper, String baseUrl) {
        this(mapper, baseUrl, new OfficialReleaseDescriptorVerifier(""));
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

    /** Fetch and locally verify the signed, immutable official release descriptor. */
    public Object releaseDescriptor(String pluginId, String version) {
        var verified = verifiedReleaseDescriptor(pluginId, version);
        try {
            return mapper.readValue(mapper.writeValueAsBytes(verified.descriptor()), Object.class);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Verified Marketplace descriptor could not be decoded");
        }
    }

    /** Returns the verified descriptor and its canonical content digest for durable provenance. */
    public VerifiedReleaseDescriptor verifiedReleaseDescriptor(String pluginId, String version) {
        if (pluginId == null || !pluginId.matches("[a-z0-9][a-z0-9._-]{0,127}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pluginId is invalid");
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version must be a semantic version");
        byte[] bytes = getBytes("/api/v1/marketplace/plugins/" + pathSegment(pluginId) + "/releases/"
                + pathSegment(version) + "/descriptor");
        var descriptor = descriptorVerifier.verify(bytes, pluginId, version);
        try {
            byte[] canonical = ReleaseDescriptorCanonicalizer.canonicalBytes(mapper.writeValueAsString(descriptor));
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical);
            return new VerifiedReleaseDescriptor(descriptor, java.util.HexFormat.of().formatHex(digest));
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Verified Marketplace descriptor could not be fingerprinted");
        }
    }

    public record VerifiedReleaseDescriptor(com.fasterxml.jackson.databind.JsonNode descriptor, String digest) { }

    /** Fetch the signed policy snapshot as bytes; the security worker verifies before parsing it. */
    public byte[] signedAdvisories() {
        return getBytes("/api/v1/marketplace/security/advisories");
    }

    private byte[] getBytes(String path) {
        if (baseUrl.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Marketplace is not configured");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String resource = path.equals("/api/v1/marketplace/security/advisories")
                            ? "Marketplace advisory feed" : "Marketplace release descriptor";
                    throw new ResponseStatusException(response.statusCode() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY,
                            resource + " is unavailable (upstream HTTP " + response.statusCode() + ")");
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace response exceeded the size limit");
                return bytes;
            }
        } catch (ResponseStatusException failure) { throw failure; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Marketplace request was interrupted");
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Marketplace could not be reached");
        }
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
