package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/public/products")
public class PublicProductController {
    private final ProductTranslationApplicationService products;

    public PublicProductController(ProductTranslationApplicationService products) {
        this.products = products;
    }

    @GetMapping("/{slug}")
    public ProductTranslationApplicationService.PublicProductView findBySlug(@PathVariable String slug,
            @RequestParam(defaultValue = "en") String locale) {
        return products.resolvePublishedBySlug(slug, locale);
    }
}
