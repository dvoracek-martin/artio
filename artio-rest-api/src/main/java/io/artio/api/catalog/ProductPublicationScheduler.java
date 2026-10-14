package io.artio.api.catalog;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes due drafts in bounded, row-locked batches. */
@Component
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
