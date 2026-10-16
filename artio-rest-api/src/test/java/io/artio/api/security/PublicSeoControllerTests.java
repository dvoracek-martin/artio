package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import io.artio.api.catalog.ProductApplicationService;
import io.artio.api.catalog.ProductResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicSeoControllerTests {
    @Test
    void emitsOnlyPublicStorefrontRoutesInTheConfiguredOriginSitemap() {
        var products = productService(List.of(product("public-sku", "home-product"), product("private-sku", null)));
        var response = new PublicSeoController("https://shop.example.test", products).sitemap();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(response.getBody()).contains(
                "<loc>https://shop.example.test/</loc>",
                "<loc>https://shop.example.test/shop</loc>",
                "<loc>https://shop.example.test/bundles</loc>",
                "<loc>https://shop.example.test/products/home-product?locale=en</loc>",
                "<loc>https://shop.example.test/products/00000000-0000-0000-0000-000000000002?locale=en</loc>");
        assertThat(response.getBody()).doesNotContain("/account", "/cart", "/checkout", "/orders");
    }

    @Test
    void advertisesTheSitemapWithoutBlockingPrivateNoindexRoutes() {
        var response = new PublicSeoController("https://shop.example.test", productService(List.of())).robots();

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getBody()).isEqualTo(
                "User-agent: *\nAllow: /\nSitemap: https://shop.example.test/sitemap.xml\n");
    }

    @Test
    void rejectsStorefrontUrlsThatCannotSafelyDefineAnOrigin() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublicSeoController("https://user:pass@shop.example.test/path", productService(List.of())));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> new PublicSeoController("javascript:alert(1)", productService(List.of())));
    }

    private static ProductApplicationService productService(List<ProductResponse> products) {
        var service = mock(ProductApplicationService.class);
        when(service.findPublishedProducts()).thenReturn(products);
        return service;
    }

    private static ProductResponse product(String sku, String slug) {
        return new ProductResponse(sku.equals("public-sku")
                ? UUID.fromString("00000000-0000-0000-0000-000000000001")
                : UUID.fromString("00000000-0000-0000-0000-000000000002"), "Product", "Product description", sku,
                true, null, null, null, Set.of(), Map.of(), slug, null, null, false, null, null);
    }
}
