package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PublicSeoControllerTests {
    @Test
    void emitsOnlyPublicStorefrontRoutesInTheConfiguredOriginSitemap() {
        var response = new PublicSeoController("https://shop.example.test").sitemap();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(response.getBody()).contains(
                "<loc>https://shop.example.test/</loc>",
                "<loc>https://shop.example.test/shop</loc>",
                "<loc>https://shop.example.test/bundles</loc>");
        assertThat(response.getBody()).doesNotContain("/account", "/cart", "/checkout", "/orders");
    }

    @Test
    void advertisesTheSitemapWithoutBlockingPrivateNoindexRoutes() {
        var response = new PublicSeoController("https://shop.example.test").robots();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getBody()).isEqualTo(
                "User-agent: *\nAllow: /\nSitemap: https://shop.example.test/sitemap.xml\n");
    }

    @Test
    void rejectsStorefrontUrlsThatCannotSafelyDefineAnOrigin() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublicSeoController("https://user:pass@shop.example.test/path"));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublicSeoController("javascript:alert(1)"));
    }
}
