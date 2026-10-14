package io.artio.api.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ReportingController {
    private final JdbcTemplate jdbc;
    public ReportingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/orders")
    public List<OrderSummary> orders() {
        return jdbc.query("SELECT id,user_id,status,total,currency,created_at,tracking_number,shipping_method_id,shipping_label_url FROM orders ORDER BY created_at DESC LIMIT 100",
                (rs,n) -> new OrderSummary(rs.getObject("id", java.util.UUID.class), rs.getObject("user_id", java.util.UUID.class), rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency").trim(), rs.getTimestamp("created_at").toInstant(),rs.getString("tracking_number"),rs.getString("shipping_method_id"),rs.getString("shipping_label_url")!=null));
    }

    @GetMapping("/reports")
    public ReportSummary reports() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        BigDecimal revenue = jdbc.queryForObject("SELECT COALESCE(SUM(total),0) FROM orders WHERE created_at>=? AND status NOT IN ('CANCELLED','RETURNED')", BigDecimal.class, since);
        Long orderCount = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE created_at>=?", Long.class, since);
        Long activeCustomers = jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM orders WHERE created_at>=?", Long.class, since);
        List<StatusCount> byStatus = jdbc.query("SELECT status,COUNT(*) AS count FROM orders GROUP BY status ORDER BY status", (rs,n) -> new StatusCount(rs.getString("status"), rs.getLong("count")));
        List<TopVariant> topVariants = jdbc.query("SELECT variant_id,SUM(quantity) AS units,SUM(quantity*unit_price) AS gross FROM order_items GROUP BY variant_id ORDER BY units DESC LIMIT 10",
                (rs,n) -> new TopVariant(rs.getObject("variant_id", java.util.UUID.class), rs.getBigDecimal("units"), rs.getBigDecimal("gross")));
        BigDecimal clv = jdbc.queryForObject("SELECT COALESCE(AVG(lifetime),0) FROM (SELECT user_id,SUM(total) lifetime FROM orders WHERE status NOT IN ('CANCELLED','RETURNED') GROUP BY user_id) customer_values", BigDecimal.class);
        return new ReportSummary(since, revenue, orderCount == null ? 0 : orderCount, activeCustomers == null ? 0 : activeCustomers, clv, byStatus, topVariants);
    }
    public record OrderSummary(java.util.UUID id, java.util.UUID userId, String status, BigDecimal total, String currency, Instant createdAt,String trackingNumber,String shippingMethodId,boolean shippingLabelAvailable) { }
    public record StatusCount(String status, long orders) { }
    public record TopVariant(java.util.UUID variantId, BigDecimal unitsSold, BigDecimal grossSales) { }
    public record ReportSummary(Instant since, BigDecimal revenue, long orders, long activeCustomers, BigDecimal averageCustomerLifetimeValue, List<StatusCount> ordersByStatus, List<TopVariant> topVariants) { }
}
