package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/variants")
public class VariantResourceController {
    private final VariantApplicationService variants;

    public VariantResourceController(VariantApplicationService variants) {
        this.variants = variants;
    }

    @GetMapping("/{id}")
    public VariantResponse findById(@PathVariable UUID id) {
        return variants.findById(id);
    }

    @PutMapping("/{id}")
    public VariantResponse update(@PathVariable UUID id, @RequestBody VariantRequest request) {
        return variants.update(id, request);
    }

    @PutMapping("/{id}/active")
    public VariantResponse setActive(@PathVariable UUID id, @RequestBody SetActiveRequest request) {
        return variants.setActive(id, request.active());
    }
}
