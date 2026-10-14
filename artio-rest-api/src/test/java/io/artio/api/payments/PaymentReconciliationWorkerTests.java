package io.artio.api.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentReconciliationWorkerTests {
    @Test
    void retriesUseExponentialBackoffCappedAtOneHour() {
        assertThat(PaymentReconciliationWorker.retryDelaySeconds(1)).isEqualTo(30);
        assertThat(PaymentReconciliationWorker.retryDelaySeconds(2)).isEqualTo(60);
        assertThat(PaymentReconciliationWorker.retryDelaySeconds(7)).isEqualTo(1920);
        assertThat(PaymentReconciliationWorker.retryDelaySeconds(8)).isEqualTo(3600);
        assertThat(PaymentReconciliationWorker.retryDelaySeconds(100)).isEqualTo(3600);
    }
}
