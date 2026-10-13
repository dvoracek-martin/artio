package io.artio.api.bundles;
import io.artio.bundles.BundleQuote; import java.net.URI; import java.util.List; import java.util.UUID; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/bundles") public class BundleController {
    private final BundleApplicationService bundles; private final BundleLocalizationApplicationService localizations; public BundleController(BundleApplicationService bundles,BundleLocalizationApplicationService localizations){this.bundles=bundles;this.localizations=localizations;}
    @GetMapping public List<BundleApplicationService.BundleView> findAll(){return bundles.findAll();}
    @GetMapping("/{id}") public BundleApplicationService.BundleView findById(@PathVariable UUID id){return bundles.findById(id);}
    @GetMapping("/localized") public List<BundleLocalizationApplicationService.LocalizedBundleView> localized(@RequestParam(defaultValue="en") String locale){return localizations.resolveAll(locale);}
    @GetMapping("/{id}/localized") public BundleLocalizationApplicationService.LocalizedBundleView localized(@PathVariable UUID id,@RequestParam(defaultValue="en") String locale){return localizations.resolve(id,locale);}
    @GetMapping("/{id}/translations/{locale}") public BundleLocalizationApplicationService.TranslationView translation(@PathVariable UUID id,@PathVariable String locale){return localizations.findTranslation(id,locale);}
    @PutMapping("/{id}/translations/{locale}") public BundleLocalizationApplicationService.TranslationView upsertTranslation(@PathVariable UUID id,@PathVariable String locale,@RequestBody BundleLocalizationApplicationService.TranslationRequest request){return localizations.upsert(id,locale,request);}
    @PostMapping public ResponseEntity<BundleApplicationService.BundleView> create(@RequestBody BundleApplicationService.BundleRequest request){var b=bundles.create(request);return ResponseEntity.created(URI.create("/api/bundles/"+b.id())).body(b);}
    @PutMapping("/{id}/active") public BundleApplicationService.BundleView setActive(@PathVariable UUID id,@RequestBody ActiveRequest request){return bundles.setActive(id,request.active());}
    @PostMapping("/{id}/configure") public BundleApplicationService.ConfigurationView configure(@PathVariable UUID id,@RequestBody BundleApplicationService.ConfigureRequest request){return bundles.configure(id,request);}
    @PostMapping("/{id}/quote") public BundleQuote quote(@PathVariable UUID id,@RequestBody BundleApplicationService.BundleQuoteRequest request){return bundles.quote(id,request);}
    public record ActiveRequest(boolean active){}
}
