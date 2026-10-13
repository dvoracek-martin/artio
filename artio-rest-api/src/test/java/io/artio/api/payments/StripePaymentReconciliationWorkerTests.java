package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StripePaymentReconciliationWorkerTests {
    @Test
    void retriesUseExponentialBackoffCappedAtOneHour() {
        assertThat(StripePaymentReconciliationWorker.retryDelaySeconds(1)).isEqualTo(30);
        assertThat(StripePaymentReconciliationWorker.retryDelaySeconds(2)).isEqualTo(60);
        assertThat(StripePaymentReconciliationWorker.retryDelaySeconds(7)).isEqualTo(1920);
        assertThat(StripePaymentReconciliationWorker.retryDelaySeconds(8)).isEqualTo(3600);
        assertThat(StripePaymentReconciliationWorker.retryDelaySeconds(100)).isEqualTo(3600);
    }
}
