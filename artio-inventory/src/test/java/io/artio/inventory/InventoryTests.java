package io.artio.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.artio.kernel.Quantity; import java.math.BigDecimal; import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryTests {
    @Test void lowStockPolicyRequiresAValidNonNegativeThreshold() {
        var policy = new LowStockPolicy(UUID.randomUUID(), new WarehouseId(UUID.randomUUID()), UUID.randomUUID(), new BigDecimal("2.5000"), true);
        assertThat(policy.threshold()).isEqualByComparingTo("2.5");
        assertThatThrownBy(() -> new LowStockPolicy(UUID.randomUUID(), policy.warehouseId(), policy.sellableId(), new BigDecimal("-1"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LowStockPolicy(UUID.randomUUID(), policy.warehouseId(), policy.sellableId(), new BigDecimal("0.00001"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void reservationsReduceAvailabilityAndAllocationConsumesOnHand() {
        var stock=StockLevel.create(new StockLevelId(UUID.randomUUID()),new WarehouseId(UUID.randomUUID()),UUID.randomUUID());
        stock.receive(new Quantity(new BigDecimal("10")));
        stock.reserve(new Quantity(new BigDecimal("4")));
        assertThat(stock.available()).isEqualByComparingTo("6");
        stock.allocate(new Quantity(new BigDecimal("4")));
        assertThat(stock.getOnHand()).isEqualByComparingTo("6");
        assertThat(stock.getReserved()).isEqualByComparingTo("0");
        assertThat(stock.available()).isEqualByComparingTo("6");
    }
    @Test void preventsOversellingAndAdjustmentBelowReservedStock() {
        var stock=StockLevel.create(new StockLevelId(UUID.randomUUID()),new WarehouseId(UUID.randomUUID()),UUID.randomUUID());
        stock.receive(new Quantity(new BigDecimal("5")));
        stock.reserve(new Quantity(new BigDecimal("3")));
        assertThatThrownBy(()->stock.reserve(new Quantity(new BigDecimal("3")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->stock.adjustTo(new Quantity(new BigDecimal("2")))).isInstanceOf(IllegalStateException.class);
        assertThat(stock.getOnHand()).isEqualByComparingTo("5");
    }
    @Test void releasedReservationCannotLaterBeAllocated() {
        var reservation=Reservation.reserve(new ReservationId(UUID.randomUUID()),UUID.randomUUID(),new WarehouseId(UUID.randomUUID()),UUID.randomUUID(),new Quantity(BigDecimal.ONE));
        reservation.release();
        assertThat(reservation.getState()).isEqualTo(ReservationState.RELEASED);
        assertThatThrownBy(reservation::allocate).isInstanceOf(IllegalStateException.class);
    }
    @Test void warehouseRequiresValidCodeAndNonBlankLocation() {
        assertThatThrownBy(()->Warehouse.create(new WarehouseId(UUID.randomUUID()),"nyc","Main","US"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->Warehouse.create(new WarehouseId(UUID.randomUUID()),"NYC-1","Main"," "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
