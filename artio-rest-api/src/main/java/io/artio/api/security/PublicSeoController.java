package io.artio.api.security;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class PublicSeoController {
    private static final List<String> PUBLIC_PATHS = List.of("/", "/shop", "/bundles");
    private final String storefrontOrigin;

    public PublicSeoController(@Value("${artio.storefront-url:http://localhost:4200}") String storefrontUrl) {
        var uri = URI.create(storefrontUrl.trim());
        if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("artio.storefront-url must be an HTTP(S) storefront origin without a path");
        }
        this.storefrontOrigin = uri.getScheme() + "://" + uri.getRawAuthority();
    }

    @GetMapping(value = "/api/v1/public/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        var entries = PUBLIC_PATHS.stream()
                .map(path -> "  <url><loc>" + xmlEscape(storefrontOrigin + path) + "</loc></url>")
                .collect(java.util.stream.Collectors.joining("\n"));
        var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n"
                + entries + "\n</urlset>\n";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .cacheControl(CacheControl.noCache()).body(xml);
    }

    @GetMapping(value = "/api/v1/public/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        var body = "User-agent: *\nAllow: /\nSitemap: " + storefrontOrigin + "/sitemap.xml\n";
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .cacheControl(CacheControl.noCache()).body(body);
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
