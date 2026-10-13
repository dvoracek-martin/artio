package io.artio.api.inventory;
import java.net.URI; import java.math.BigDecimal; import java.util.List; import java.util.UUID; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") public class InventoryController {
    private final InventoryApplicationService inventory; public InventoryController(InventoryApplicationService inventory){this.inventory=inventory;}
    @GetMapping("/warehouses") public List<InventoryApplicationService.WarehouseView> warehouses(){return inventory.warehouses();}
    @PostMapping("/warehouses") public ResponseEntity<InventoryApplicationService.WarehouseView> createWarehouse(@RequestBody InventoryApplicationService.WarehouseRequest request){var w=inventory.createWarehouse(request);return ResponseEntity.created(URI.create("/api/warehouses/"+w.id())).body(w);}
    @PutMapping("/warehouses/{id}/active") public InventoryApplicationService.WarehouseView warehouseActive(@PathVariable UUID id,@RequestBody ActiveRequest request){return inventory.setWarehouseActive(id,request.active());}
    @GetMapping("/warehouses/{id}/stock") public List<InventoryApplicationService.StockView> stockByWarehouse(@PathVariable UUID id){return inventory.stockByWarehouse(id);}
    @GetMapping("/stock/{sellableId}") public List<InventoryApplicationService.StockView> stockBySellable(@PathVariable UUID sellableId){return inventory.stockBySellable(sellableId);}
    @PostMapping("/warehouses/{warehouseId}/stock/{sellableId}/receipts") public InventoryApplicationService.StockView receive(@PathVariable UUID warehouseId,@PathVariable UUID sellableId,@RequestBody QuantityRequest request){return inventory.receive(warehouseId,sellableId,request.quantity());}
    @PutMapping("/warehouses/{warehouseId}/stock/{sellableId}") public InventoryApplicationService.StockView adjust(@PathVariable UUID warehouseId,@PathVariable UUID sellableId,@RequestBody QuantityRequest request){return inventory.adjust(warehouseId,sellableId,request.quantity());}
    @PostMapping("/inventory/reservations") public ResponseEntity<InventoryApplicationService.ReservationView> reserve(@RequestBody InventoryApplicationService.ReserveRequest request){var r=inventory.reserve(request);return ResponseEntity.created(URI.create("/api/inventory/reservations/"+r.id())).body(r);}
    @GetMapping("/orders/{orderId}/inventory-reservations") public List<InventoryApplicationService.ReservationView> byOrder(@PathVariable UUID orderId){return inventory.reservationsByOrder(orderId);}
    @PutMapping("/inventory/reservations/{id}/allocate") public InventoryApplicationService.ReservationView allocate(@PathVariable UUID id){return inventory.allocate(id);}
    @PutMapping("/inventory/reservations/{id}/release") public InventoryApplicationService.ReservationView release(@PathVariable UUID id){return inventory.release(id);}
    public record QuantityRequest(BigDecimal quantity){} public record ActiveRequest(boolean active){}
}
