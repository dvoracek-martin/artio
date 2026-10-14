package io.artio.api.orders;

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

@RestController
public class CustomerOrderController {
    private final JdbcTemplate jdbc;
    public CustomerOrderController(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping("/api/orders/{orderId}/items")
    public List<CustomerOrderLine> items(@PathVariable UUID orderId, JwtAuthenticationToken auth) {
        UUID customerId=UUID.fromString(auth.getToken().getSubject());
        if(jdbc.query("SELECT 1 FROM orders WHERE id=? AND user_id=?",(rs,row)->rs.getInt(1),orderId,customerId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found");
        return jdbc.query("SELECT id,variant_id,bundle_id,quantity,unit_price,currency FROM order_items WHERE order_id=? ORDER BY id",
                (rs,row)->new CustomerOrderLine(rs.getObject("id",UUID.class),rs.getObject("variant_id",UUID.class),rs.getObject("bundle_id",UUID.class),
                        rs.getBigDecimal("quantity"),rs.getBigDecimal("unit_price"),rs.getString("currency").trim()),orderId);
    }
    public record CustomerOrderLine(UUID orderItemId,UUID variantId,UUID bundleId,BigDecimal quantity,BigDecimal unitPrice,String currency){}
}
