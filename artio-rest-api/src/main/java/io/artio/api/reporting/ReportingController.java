package io.artio.api.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/v1/admin")
public class ReportingController {
    private final JdbcTemplate jdbc;
    private final ReportingService reporting;
    public ReportingController(JdbcTemplate jdbc, ReportingService reporting) { this.jdbc = jdbc; this.reporting = reporting; }

    @GetMapping("/orders/recent")
    public List<OrderSummary> orders() {
        return jdbc.query("SELECT id,user_id,status,total,currency,created_at,tracking_number,shipping_method_id,shipping_label_url FROM orders ORDER BY created_at DESC LIMIT 100",
                (rs,n) -> new OrderSummary(rs.getObject("id", java.util.UUID.class), rs.getObject("user_id", java.util.UUID.class), rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency").trim(), rs.getTimestamp("created_at").toInstant(),rs.getString("tracking_number"),rs.getString("shipping_method_id"),rs.getString("shipping_label_url")!=null));
    }

    @GetMapping("/reports")
    public ReportingService.StandardReport reports(@RequestParam(defaultValue="30") int days) { return reporting.standard(days); }

    @GetMapping("/reports/advanced")
    public ReportingService.AdvancedReport advanced(@RequestParam(defaultValue="30") int days) { return reporting.advanced(days); }
    public record OrderSummary(java.util.UUID id, java.util.UUID userId, String status, BigDecimal total, String currency, Instant createdAt,String trackingNumber,String shippingMethodId,boolean shippingLabelAvailable) { }
}
