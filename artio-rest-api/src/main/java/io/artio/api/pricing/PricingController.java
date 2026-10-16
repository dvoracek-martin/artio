package io.artio.api.pricing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;import java.net.URI; import java.util.List; import java.util.UUID; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController @RequestMapping("/api/v1") public class PricingController {
    private final PricingApplicationService pricing; public PricingController(PricingApplicationService pricing){this.pricing=pricing;}
    @GetMapping("/prices") public List<PricingApplicationService.PriceView> prices(){return pricing.prices();}
    @PostMapping("/prices") public ResponseEntity<PricingApplicationService.PriceView> createPrice(@RequestBody PricingApplicationService.PriceRequest request){var p=pricing.createPrice(request);return ResponseEntity.created(URI.create("/api/v1/prices/"+p.id())).body(p);}
    @PutMapping("/prices/{id}") public PricingApplicationService.PriceView updatePrice(@PathVariable UUID id,@RequestBody PricingApplicationService.PriceUpdateRequest request){return pricing.updatePrice(id,request);}
    @PutMapping("/prices/{id}/active") public PricingApplicationService.PriceView setPriceActive(@PathVariable UUID id,@RequestBody ActiveRequest request){return pricing.setPriceActive(id,request.active());}
    @GetMapping("/discount-rules") public List<PricingApplicationService.DiscountView> discounts(){return pricing.discounts();}
    @PostMapping("/discount-rules") public ResponseEntity<PricingApplicationService.DiscountView> createDiscount(@RequestBody PricingApplicationService.DiscountRequest request){var d=pricing.createDiscount(request);return ResponseEntity.created(URI.create("/api/v1/discount-rules/"+d.id())).body(d);}
    @PutMapping("/discount-rules/{id}/active") public PricingApplicationService.DiscountView setDiscountActive(@PathVariable UUID id,@RequestBody ActiveRequest request){return pricing.setDiscountActive(id,request.active());}
    @GetMapping("/tax-rules") public List<PricingApplicationService.TaxView> taxes(){return pricing.taxRules();}
    @GetMapping("/tax-rules/sellables/categories") public List<TaxCategoryView> assignedCategories(){return pricing.assignedTaxCategories().entrySet().stream().map(entry->new TaxCategoryView(entry.getKey(),entry.getValue())).toList();}
    @PostMapping("/tax-rules") public ResponseEntity<PricingApplicationService.TaxView> createTax(@RequestBody PricingApplicationService.TaxRequest request){var t=pricing.createTaxRule(request);return ResponseEntity.created(URI.create("/api/v1/tax-rules/"+t.id())).body(t);}
    @PutMapping("/tax-rules/{id}") public PricingApplicationService.TaxView updateTax(@PathVariable UUID id,@RequestBody PricingApplicationService.TaxRequest request){return pricing.updateTaxRule(id,request);}
    @DeleteMapping("/tax-rules/{id}") public ResponseEntity<Void> deleteTax(@PathVariable UUID id){pricing.deleteTaxRule(id);return ResponseEntity.noContent().build();}
    @PutMapping("/tax-rules/{id}/active") public PricingApplicationService.TaxView setTaxActive(@PathVariable UUID id,@RequestBody ActiveRequest request){return pricing.setTaxActive(id,request.active());}
    @PutMapping("/tax-rules/sellables/{sellableId}/category") public TaxCategoryView assignTaxCategory(@PathVariable UUID sellableId,@RequestBody TaxCategoryRequest request){return new TaxCategoryView(sellableId,pricing.assignTaxCategory(sellableId,request.category()));}
    @PostMapping("/pricing/quote") public io.artio.pricing.PricingQuote quote(@RequestBody PricingApplicationService.QuoteRequest request){return pricing.quote(request);}
    public record ActiveRequest(boolean active){}
    public record TaxCategoryRequest(io.artio.pricing.TaxCategory category){}
    public record TaxCategoryView(UUID sellableId,io.artio.pricing.TaxCategory category){}
}
