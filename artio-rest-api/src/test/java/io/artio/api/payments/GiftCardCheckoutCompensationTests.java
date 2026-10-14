package io.artio.api.payments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.artio.api.enterprise.GiftCardAccounting;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GiftCardCheckoutCompensationTests {
    @Test
    void restoresDisabledCardOnlyOnceForTheSamePaymentRefund() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID cardId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(jdbc.update(startsWith("INSERT INTO gift_card_transactions"), any(Object[].class))).thenReturn(1, 0);
        when(jdbc.update(startsWith("UPDATE gift_cards SET balance=balance+"), any(Object[].class))).thenReturn(1);

        GiftCardAccounting.restoreRejectedCheckout(jdbc, cardId, userId, orderId, refundId, new BigDecimal("5.00"));
        GiftCardAccounting.restoreRejectedCheckout(jdbc, cardId, userId, orderId, refundId, new BigDecimal("5.00"));

        verify(jdbc, times(2)).update(startsWith("INSERT INTO gift_card_transactions"), any(Object[].class));
        verify(jdbc).update(startsWith("UPDATE gift_cards SET balance=balance+"), any(Object[].class));
    }
}
