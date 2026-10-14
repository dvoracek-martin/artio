package io.artio.api.orders;

import io.artio.api.inventory.InventoryApplicationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Customer return requests and staff-controlled review, receipt and restocking. */
@RestController
public class OrderReturnController {
    private final JdbcTemplate jdbc;
    private final InventoryApplicationService inventory;

    public OrderReturnController(JdbcTemplate jdbc, InventoryApplicationService inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    @PostMapping("/api/orders/{orderId}/returns")
    @Transactional
    public ReturnView request(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReturnRequest request, JwtAuthenticationToken auth) {
        if (key == null || key.isBlank() || key.length() > 128 || request == null || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 1000 || request.items() == null
                || request.items().isEmpty() || request.items().size() > 50
                || request.items().stream().anyMatch(java.util.Objects::isNull)
                || request.customerNote() != null && request.customerNote().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason and 1–50 valid return lines are required");
        }
        List<UUID> duplicateItemIds = request.items().stream().map(ReturnLineRequest::orderItemId).toList();
        if (duplicateItemIds.contains(null) || duplicateItemIds.stream().distinct().count() != duplicateItemIds.size()
                || request.items().stream().anyMatch(line -> line.quantity() < 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return lines must have unique order items and positive quantities");
        UUID customerId = userId(auth);
        String orderStatus = jdbc.query("SELECT status FROM orders WHERE id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), orderId, customerId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!List.of("SHIPPED", "DELIVERED", "RETURNED").contains(orderStatus))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only shipped orders can be returned");

        var prior = jdbc.query("SELECT id,order_id,status,reason,customer_note,staff_note,created_at,reviewed_at,completed_at "
                        + "FROM order_returns WHERE customer_id=? AND idempotency_key=?",
                (rs, row) -> view(rs), customerId, key);
        if (!prior.isEmpty()) {
            UUID returnId = prior.getFirst().id();
            Map<UUID, Integer> saved = lineQuantities(returnId);
            Map<UUID, Integer> requested = request.items().stream().collect(java.util.stream.Collectors.toMap(
                    ReturnLineRequest::orderItemId, ReturnLineRequest::quantity));
            if (!saved.equals(requested) || !prior.getFirst().orderId().equals(orderId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for a different return");
            return prior.getFirst().withItems(loadLines(returnId));
        }

        for (ReturnLineRequest line : request.items()) {
            Integer ordered = jdbc.query("SELECT quantity FROM order_items WHERE id=? AND order_id=? FOR UPDATE",
                    (rs, row) -> rs.getInt(1), line.orderItemId(), orderId).stream().findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Return line does not belong to this order"));
            Integer reserved = jdbc.queryForObject("SELECT COALESCE(sum(ri.quantity),0)::integer FROM order_return_items ri "
                    + "JOIN order_returns r ON r.id=ri.return_id WHERE ri.order_item_id=? AND r.status IN ('REQUESTED','APPROVED','COMPLETED')",
                    Integer.class, line.orderItemId());
            if (line.quantity() > ordered - reserved)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Requested return quantity exceeds the unreturned order quantity");
        }
        UUID returnId = UUID.randomUUID();
        jdbc.update("INSERT INTO order_returns(id,order_id,customer_id,status,reason,customer_note,idempotency_key) VALUES (?,?,?,'REQUESTED',?,?,?)",
                returnId, orderId, customerId, request.reason().trim(), request.customerNote(), key);
        for (ReturnLineRequest line : request.items()) jdbc.update(
                "INSERT INTO order_return_items(id,return_id,order_item_id,quantity) VALUES (?,?,?,?)",
                UUID.randomUUID(), returnId, line.orderItemId(), line.quantity());
        event(returnId, customerId, "REQUESTED", request.customerNote());
        OrderTimelineEvents.record(jdbc,orderId,customerId,"RETURN_REQUESTED","Return requested: "+request.reason().trim(),true);
        return new ReturnView(returnId, orderId, "REQUESTED", request.reason().trim(), request.customerNote(), null,
                Instant.now(), null, null, loadLines(returnId));
    }

    @GetMapping("/api/orders/{orderId}/returns")
    public List<ReturnView> customerReturns(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        UUID customerId = userId(auth);
        if (jdbc.query("SELECT 1 FROM orders WHERE id=? AND user_id=?", (rs, row) -> rs.getInt(1), orderId, customerId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return findReturns(orderId);
    }

    @GetMapping("/api/admin/orders/{orderId}/returns")
    public List<ReturnView> adminReturns(@PathVariable UUID orderId) {
        if (jdbc.query("SELECT 1 FROM orders WHERE id=?", (rs, row) -> rs.getInt(1), orderId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return findReturns(orderId);
    }

    @PostMapping("/api/admin/orders/{orderId}/returns/{returnId}/review")
    @Transactional
    public ReturnView review(@PathVariable UUID orderId, @PathVariable UUID returnId, @RequestBody ReviewRequest request,
            JwtAuthenticationToken auth) {
        if (request == null || request.approve() == null || request.note() != null && request.note().length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A review decision and a valid note are required");
        ReturnView current = lockReturn(orderId, returnId);
        if (!"REQUESTED".equals(current.status())) {
            if (request.approve() == "APPROVED".equals(current.status())) return current.withItems(loadLines(returnId));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Return has already been reviewed");
        }
        String next = request.approve() ? "APPROVED" : "REJECTED";
        jdbc.update("UPDATE order_returns SET status=?,staff_note=?,reviewed_at=now() WHERE id=?", next, request.note(), returnId);
        event(returnId, userId(auth), next, request.note());
        OrderTimelineEvents.record(jdbc,orderId,userId(auth),"RETURN_"+next,"Return request "+next.toLowerCase(java.util.Locale.ROOT),false);
        return current.with(next, request.note(), Instant.now(), null, loadLines(returnId));
    }

    @PostMapping("/api/admin/orders/{orderId}/returns/{returnId}/receive")
    @Transactional
    public ReturnView receive(@PathVariable UUID orderId, @PathVariable UUID returnId, @RequestBody ReceiveRequest request,
            JwtAuthenticationToken auth) {
        ReturnView current = lockReturn(orderId, returnId);
        if ("COMPLETED".equals(current.status())) return current.withItems(loadLines(returnId));
        if (!"APPROVED".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only approved returns can be received");
        if (request == null || request.restock() && request.warehouseId() == null
                || request.note() != null && request.note().length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Restocking requires a warehouse");
        List<ReturnLine> lines = loadLines(returnId);
        for (ReturnLine line : lines) {
            if (request.restock()) {
                UUID variantId = jdbc.queryForObject("SELECT variant_id FROM order_items WHERE id=?", UUID.class, line.orderItemId());
                inventory.receive(request.warehouseId(), variantId, java.math.BigDecimal.valueOf(line.quantity()),
                        "Customer return " + returnId, userId(auth));
            }
            jdbc.update("UPDATE order_return_items SET restock_warehouse_id=?,restocked_at=? WHERE id=?",
                    request.restock() ? request.warehouseId() : null, request.restock() ? java.sql.Timestamp.from(Instant.now()) : null, line.id());
        }
        jdbc.update("UPDATE order_returns SET status='COMPLETED',staff_note=?,completed_at=now() WHERE id=?", request.note(), returnId);
        event(returnId, userId(auth), "COMPLETED", request.note());
        OrderTimelineEvents.record(jdbc,orderId,userId(auth),"RETURN_COMPLETED",request.restock()?"Return received and restocked":"Return received without restocking",false);
        if (allOrderLinesReturned(orderId)) jdbc.update("UPDATE orders SET status='RETURNED' WHERE id=? AND status IN ('SHIPPED','DELIVERED')", orderId);
        return current.with("COMPLETED", request.note(), current.reviewedAt(), Instant.now(), loadLines(returnId));
    }

    private ReturnView lockReturn(UUID orderId, UUID returnId) {
        return jdbc.query("SELECT id,order_id,status,reason,customer_note,staff_note,created_at,reviewed_at,completed_at "
                        + "FROM order_returns WHERE id=? AND order_id=? FOR UPDATE", (rs, row) -> view(rs), returnId, orderId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Return not found"));
    }
    private List<ReturnView> findReturns(UUID orderId) {
        List<ReturnView> result = jdbc.query("SELECT id,order_id,status,reason,customer_note,staff_note,created_at,reviewed_at,completed_at "
                + "FROM order_returns WHERE order_id=? ORDER BY created_at DESC", (rs, row) -> view(rs), orderId);
        return result.stream().map(r -> r.withItems(loadLines(r.id()))).toList();
    }
    private static ReturnView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReturnView(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("status"),
                rs.getString("reason"), rs.getString("customer_note"), rs.getString("staff_note"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(), List.of());
    }
    private List<ReturnLine> loadLines(UUID returnId) {
        return jdbc.query("SELECT id,order_item_id,quantity,restock_warehouse_id,restocked_at FROM order_return_items WHERE return_id=? ORDER BY id",
                (rs, row) -> new ReturnLine(rs.getObject("id", UUID.class), rs.getObject("order_item_id", UUID.class), rs.getInt("quantity"),
                        rs.getObject("restock_warehouse_id", UUID.class), rs.getTimestamp("restocked_at") == null ? null : rs.getTimestamp("restocked_at").toInstant()), returnId);
    }
    private Map<UUID, Integer> lineQuantities(UUID returnId) {
        return jdbc.query("SELECT order_item_id,quantity FROM order_return_items WHERE return_id=?",
                rs -> { var result = new java.util.HashMap<UUID, Integer>(); while (rs.next()) result.put(rs.getObject(1, UUID.class), rs.getInt(2)); return result; }, returnId);
    }
    private boolean allOrderLinesReturned(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id=? AND oi.quantity > "
                + "COALESCE((SELECT sum(ri.quantity) FROM order_return_items ri JOIN order_returns r ON r.id=ri.return_id "
                + "WHERE ri.order_item_id=oi.id AND r.status='COMPLETED'),0))", Boolean.class, orderId));
    }
    private void event(UUID returnId, UUID actorId, String type, String note) {
        jdbc.update("INSERT INTO order_return_events(id,return_id,actor_id,event_type,note) VALUES (?,?,?,?,?)", UUID.randomUUID(), returnId, actorId, type, note);
    }
    private static UUID userId(JwtAuthenticationToken auth) { return UUID.fromString(auth.getToken().getSubject()); }

    public record ReturnRequest(String reason, String customerNote, List<ReturnLineRequest> items) { }
    public record ReturnLineRequest(UUID orderItemId, int quantity) { }
    public record ReviewRequest(Boolean approve, String note) { }
    public record ReceiveRequest(boolean restock, UUID warehouseId, String note) { }
    public record ReturnLine(UUID id, UUID orderItemId, int quantity, UUID restockWarehouseId, Instant restockedAt) { }
    public record ReturnView(UUID id, UUID orderId, String status, String reason, String customerNote, String staffNote,
            Instant createdAt, Instant reviewedAt, Instant completedAt, List<ReturnLine> items) {
        ReturnView withItems(List<ReturnLine> lines) { return new ReturnView(id,orderId,status,reason,customerNote,staffNote,createdAt,reviewedAt,completedAt,lines); }
        ReturnView with(String nextStatus, String note, Instant reviewed, Instant completed, List<ReturnLine> lines) {
            return new ReturnView(id,orderId,nextStatus,reason,customerNote,note,createdAt,reviewed,completed,lines);
        }
    }
}
