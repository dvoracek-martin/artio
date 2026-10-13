package io.artio.api.payments;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.orders.OrderUpdatePublisher;
import io.artio.inventory.ReservationState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CheckoutFailureCompensationTests {
    @Test
    void cancelsRejectedOrderAndReleasesItsReservations() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InventoryApplicationService inventory = mock(InventoryApplicationService.class);
        var reservation = new InventoryApplicationService.ReservationView(reservationId, orderId,
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, ReservationState.RESERVED, Instant.now());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId))).thenReturn(List.of("PENDING"));
        when(jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=? AND status='PENDING'", orderId)).thenReturn(1);
        when(inventory.reservationsByOrder(orderId)).thenReturn(List.of(reservation));

        new CheckoutFailureCompensation(jdbc, inventory, mock(OrderUpdatePublisher.class))
                .cancelRejectedCheckout(orderId);

        verify(jdbc).update("UPDATE payments SET status='FAILED',updated_at=now() WHERE order_id=? AND status='PENDING'", orderId);
        verify(inventory).release(reservationId);
    }

    @Test
    void leavesOrdersAloneWhenTheyAreNoLongerPending() {
        UUID orderId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        InventoryApplicationService inventory = mock(InventoryApplicationService.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(orderId))).thenReturn(List.of("PAID"));

        new CheckoutFailureCompensation(jdbc, inventory, mock(OrderUpdatePublisher.class))
                .cancelRejectedCheckout(orderId);

        verify(jdbc, never()).update(anyString(), any(Object.class));
        verify(inventory, never()).reservationsByOrder(orderId);
    }
}
