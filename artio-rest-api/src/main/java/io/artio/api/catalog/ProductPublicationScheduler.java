package io.artio.api.catalog;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Publishes due drafts in bounded, row-locked batches. */
@Component
@ConditionalOnProperty(name = "artio.workers.enabled", havingValue = "true", matchIfMissing = true)
public class ProductPublicationScheduler {
    private final ProductApplicationService products;

    public ProductPublicationScheduler(ProductApplicationService products) {
        this.products = products;
    }

    @Scheduled(fixedDelayString = "${artio.catalog.publication-poll-interval-ms:15000}")
    public void publishDueProducts() {
        products.publishDueProducts(Instant.now(), 100);
    }
}
