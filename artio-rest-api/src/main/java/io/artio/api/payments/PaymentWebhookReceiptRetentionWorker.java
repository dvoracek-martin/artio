package io.artio.api.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded cleanup for already processed webhook deduplication receipts. */
@Component
@ConditionalOnProperty(name = "artio.workers.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentWebhookReceiptRetentionWorker {
    private static final int BATCH_SIZE = 1000;
    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public PaymentWebhookReceiptRetentionWorker(JdbcTemplate jdbc,
            @Value("${artio.payments.webhook-receipt-retention-days:90}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("Webhook receipt retention must be between 1 and 3650 days");
        }
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${artio.payments.webhook-receipt-retention-cron:0 17 3 * * *}")
    public int removeExpiredReceipts() {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    WITH expired AS (
                        SELECT provider,event_id FROM payment_webhook_receipts
                        WHERE processed_at IS NOT NULL
                          AND processed_at < now() - (? * interval '1 day')
                        ORDER BY processed_at
                        LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM payment_webhook_receipts receipt
                    USING expired
                    WHERE receipt.provider=expired.provider AND receipt.event_id=expired.event_id
                    """, retentionDays, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
