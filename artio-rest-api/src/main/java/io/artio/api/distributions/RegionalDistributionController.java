package io.artio.api.distributions;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only discovery for the regional defaults shipped with Artio Core. */
@RestController
@RequestMapping("/api/public/distributions")
public class RegionalDistributionController {
    private final RegionalDistributionCatalog catalog;

    public RegionalDistributionController(RegionalDistributionCatalog catalog) { this.catalog = catalog; }

    @GetMapping
    public List<RegionalDistribution> findAll() { return catalog.findAll(); }

    @GetMapping("/{id}")
    public RegionalDistribution find(@PathVariable String id) { return catalog.find(id); }
}
