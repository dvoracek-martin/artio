package io.artio.api.catalog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative maintenance operations for the derived catalog search index. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/admin/catalog/search-index")
public class CatalogSearchAdminController {
    private final CatalogSearchReindexService reindex;

    public CatalogSearchAdminController(CatalogSearchReindexService reindex) {
        this.reindex = reindex;
    }

    @PostMapping("/rebuild")
    public CatalogSearchReindexService.ReindexResult rebuild() {
        return reindex.reindex();
    }
}
