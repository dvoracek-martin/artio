package io.artio.api.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
public class CustomerOrderController {
    private final JdbcTemplate jdbc;
    public CustomerOrderController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping("/api/v1/orders/{orderId}/items")
    public List<CustomerOrderLine> items(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        UUID customerId=UUID.fromString(auth.getToken().getSubject());
        if(jdbc.query("SELECT 1 FROM orders WHERE id=? AND user_id=?",(rs,row)->rs.getInt(1),orderId,customerId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("SELECT oi.id,oi.variant_id,oi.bundle_id,oi.quantity,oi.unit_price,oi.currency,"
                        + "CASE WHEN o.status IN ('SHIPPED','DELIVERED','RETURNED') THEN oi.quantity ELSE "
                        + "floor(COALESCE((SELECT sum(si.quantity) FROM order_shipment_items si JOIN order_shipments s ON s.id=si.shipment_id "
                        + "WHERE s.order_id=oi.order_id AND si.order_item_id=oi.id),0)) END AS shipped_quantity,"
                        + "GREATEST((CASE WHEN o.status IN ('SHIPPED','DELIVERED','RETURNED') THEN oi.quantity ELSE "
                        + "floor(COALESCE((SELECT sum(si.quantity) FROM order_shipment_items si JOIN order_shipments s ON s.id=si.shipment_id "
                        + "WHERE s.order_id=oi.order_id AND si.order_item_id=oi.id),0)) END)-COALESCE((SELECT sum(ri.quantity) "
                        + "FROM order_return_items ri JOIN order_returns r ON r.id=ri.return_id WHERE ri.order_item_id=oi.id "
                        + "AND r.status IN ('REQUESTED','APPROVED','COMPLETED')),0),0) AS returnable_quantity "
                        + "FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE oi.order_id=? ORDER BY oi.id",
                (rs,row)->new CustomerOrderLine(rs.getObject("id",UUID.class),rs.getObject("variant_id",UUID.class),rs.getObject("bundle_id",UUID.class),
                        rs.getBigDecimal("quantity"),rs.getBigDecimal("shipped_quantity"),rs.getBigDecimal("returnable_quantity"),
                        rs.getBigDecimal("unit_price"),rs.getString("currency").trim()),orderId);
    }
    public record CustomerOrderLine(UUID orderItemId,UUID variantId,UUID bundleId,BigDecimal quantity,BigDecimal shippedQuantity,
            BigDecimal returnableQuantity,BigDecimal unitPrice,String currency){}
}
