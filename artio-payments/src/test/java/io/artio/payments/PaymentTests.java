package io.artio.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.artio.kernel.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTests {
    @Test void paymentStateTransitionsEnforceCaptureAndRefundOrder() {
        var payment=Payment.pending(new PaymentId(UUID.randomUUID()),UUID.randomUUID(),new Money(new BigDecimal("42.00"),Currency.getInstance("USD")),PaymentMethod.STRIPE,"pi_123");
        assertThatThrownBy(payment::capture).isInstanceOf(IllegalStateException.class);
        payment.authorize();payment.capture();assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
        payment.refund();assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThatThrownBy(payment::capture).isInstanceOf(IllegalStateException.class);
    }
}
