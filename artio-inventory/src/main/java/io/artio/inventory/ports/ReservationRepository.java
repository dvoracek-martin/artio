package io.artio.inventory.ports;
import io.artio.inventory.Reservation; import io.artio.inventory.ReservationId; import io.artio.inventory.WarehouseId; import java.util.List; import java.util.Optional; import java.util.UUID;
public interface ReservationRepository { Optional<Reservation> findById(ReservationId id); Optional<Reservation> findByOrderAndWarehouseAndSellable(UUID orderId,WarehouseId warehouseId,UUID sellableId); List<Reservation> findByOrder(UUID orderId); void save(Reservation reservation); }
