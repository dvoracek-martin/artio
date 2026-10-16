package io.artio.api.extensions;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Polls Marketplace outbound only; invalid or unavailable feeds never mutate verified policy. */
@Component
@ConditionalOnProperty(prefix = "artio.workers", name = "enabled", havingValue = "true")
public final class MarketplaceAdvisorySyncWorker {
  private static final Logger log = LoggerFactory.getLogger(MarketplaceAdvisorySyncWorker.class);
  private final MarketplaceCatalogClient marketplace;
  private final SignedMarketplaceAdvisoryVerifier verifier;
  private final MarketplaceAdvisoryStore store;

  public MarketplaceAdvisorySyncWorker(
      MarketplaceCatalogClient marketplace,
      SignedMarketplaceAdvisoryVerifier verifier,
      MarketplaceAdvisoryStore store) {
    this.marketplace = marketplace;
    this.verifier = verifier;
    this.store = store;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void syncOnStartup() {
    syncSafely();
  }

  @Scheduled(fixedDelayString = "${artio.marketplace.advisories.poll-interval-ms:21600000}")
  public void poll() {
    syncSafely();
  }

  public void syncSafely() {
    try {
      byte[] bytes = marketplace.signedAdvisories();
      var snapshot = verifier.verify(bytes, Instant.now());
      var result = store.apply(snapshot);
      log.info(
          "Marketplace security advisory snapshot {} at sequence {}",
          result.changed() ? "applied" : "confirmed",
          result.sequence());
    } catch (ResponseStatusException rejected) {
      store.recordFailure("REJECTED", null, null, rejected.getClass().getSimpleName());
      log.warn("Marketplace security advisory snapshot was rejected: {}", rejected.getReason());
    } catch (IllegalArgumentException rejected) {
      store.recordFailure("REJECTED", null, null, rejected.getClass().getSimpleName());
      log.warn("Marketplace security advisory snapshot was rejected: {}", rejected.getClass().getSimpleName());
    } catch (Exception unavailable) {
      store.recordFailure("UNAVAILABLE", null, null, unavailable.getClass().getSimpleName());
      log.warn("Marketplace security advisory refresh failed: {}", unavailable.getClass().getSimpleName());
    }
  }
}
