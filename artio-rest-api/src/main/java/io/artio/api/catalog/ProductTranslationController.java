package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/products/{productId}")
public class ProductTranslationController {
    private final ProductTranslationApplicationService translations;

    public ProductTranslationController(ProductTranslationApplicationService translations) {
        this.translations = translations;
    }

    @GetMapping("/localized")
    public ProductTranslationApplicationService.LocalizedProductView localized(@PathVariable UUID productId,
            @RequestParam(defaultValue = "en") String locale) {
        return translations.resolve(productId, locale);
    }

    @GetMapping("/translations")
    public List<ProductTranslationApplicationService.TranslationView> findAll(@PathVariable UUID productId) {
        return translations.findAll(productId);
    }

    @PutMapping("/translations/{locale}")
    public ProductTranslationApplicationService.TranslationView upsert(@PathVariable UUID productId,
            @PathVariable String locale, @RequestBody ProductTranslationApplicationService.TranslationRequest request) {
        return translations.upsert(productId, locale, request);
    }
}
