package io.artio.api.reporting;

import io.artio.api.licensing.EnterpriseFeature;
import io.artio.api.licensing.LicensingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Operational order-cohort metrics. Money is never aggregated across currencies or implicitly converted. */
@Service
public class ReportingService {
    private static final String COHORT = """
            WITH selected_orders AS (
                SELECT id,user_id,currency,total FROM orders WHERE created_at>=? AND created_at<?
            ), payment_totals AS (
                SELECT p.order_id,SUM(COALESCE(p.captured_amount,p.amount)) captured,SUM(p.refunded_amount) refunded,SUM(p.reversed_amount) reversed
                FROM payments p JOIN selected_orders o ON o.id=p.order_id AND o.currency=p.currency
                WHERE p.status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED','REVERSED') GROUP BY p.order_id
            )
            """;
    private final JdbcTemplate jdbc;
    private final LicensingService licensing;

    public ReportingService(JdbcTemplate jdbc, LicensingService licensing) {
        this.jdbc = jdbc;
        this.licensing = licensing;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StandardReport standard(int days) {
        Window window = window(days);
        var totals = jdbc.queryForObject("SELECT count(*),count(DISTINCT user_id) FROM orders WHERE created_at>=? AND created_at<?",
                (rs, row) -> new Counts(rs.getLong(1), rs.getLong(2)), window.start(), window.end());
        var statuses = jdbc.query("SELECT status,count(*) FROM orders WHERE created_at>=? AND created_at<? GROUP BY status ORDER BY status",
                (rs, row) -> new StatusCount(rs.getString(1), rs.getLong(2)), window.start(), window.end());
        var currencies = jdbc.query(COHORT + """
                SELECT o.currency,count(*) orders,count(DISTINCT o.user_id) customers,
                    COALESCE(SUM(p.captured),0) captured,COALESCE(SUM(p.refunded),0) refunded,COALESCE(SUM(p.reversed),0) reversed
                FROM selected_orders o LEFT JOIN payment_totals p ON p.order_id=o.id
                GROUP BY o.currency ORDER BY o.currency
                """, (rs, row) -> new CurrencySales(rs.getString("currency").trim(), rs.getLong("orders"),
                        rs.getLong("customers"), rs.getBigDecimal("captured"), rs.getBigDecimal("refunded"),
                        rs.getBigDecimal("reversed"), rs.getBigDecimal("captured").subtract(rs.getBigDecimal("refunded")).subtract(rs.getBigDecimal("reversed"))), window.start(), window.end());
        return new StandardReport(window.since(), window.until(), totals.orders(), totals.customers(), statuses, currencies);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdvancedReport advanced(int days) {
        validateDays(days);
        licensing.require(EnterpriseFeature.ADVANCED_REPORTING);
        Window window = window(days);
        var variants = jdbc.query(COHORT + """
                , variant_totals AS (
                    SELECT o.currency,i.variant_id,SUM(i.quantity) units,
                        SUM(i.quantity*i.unit_price) recorded_line_value
                    FROM selected_orders o JOIN payment_totals p ON p.order_id=o.id
                    JOIN order_items i ON i.order_id=o.id AND i.currency=o.currency
                    WHERE p.captured-p.reversed>=o.total AND p.captured>0
                    GROUP BY o.currency,i.variant_id
                ), ranked AS (
                    SELECT *,row_number() OVER (PARTITION BY currency ORDER BY units DESC,variant_id) position
                    FROM variant_totals
                )
                SELECT r.currency,r.variant_id,r.units,r.recorded_line_value,r.position,
                    v.sku,v.name variant_name,c.id product_id,c.name product_name
                FROM ranked r LEFT JOIN catalog_variant v ON v.id=r.variant_id
                LEFT JOIN catalog_product c ON c.id=v.product_id
                WHERE r.position<=10 ORDER BY r.currency,r.position
                """, (rs, row) -> new TopVariant(rs.getString("currency").trim(), rs.getObject("variant_id", UUID.class),
                        rs.getString("sku"),rs.getString("variant_name"),rs.getObject("product_id",UUID.class),rs.getString("product_name"),
                        rs.getBigDecimal("units"), rs.getBigDecimal("recorded_line_value"), rs.getInt("position")), window.start(), window.end());
        var lifetime = jdbc.query("""
                WITH customer_values AS (
                    SELECT p.currency,o.user_id,SUM(COALESCE(p.captured_amount,p.amount)) captured,SUM(p.refunded_amount) refunded,SUM(p.reversed_amount) reversed
                    FROM payments p JOIN orders o ON o.id=p.order_id AND o.currency=p.currency
                    WHERE o.created_at<? AND p.status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED','REVERSED')
                    GROUP BY p.currency,o.user_id
                )
                SELECT currency,count(*) customers,SUM(captured) captured,SUM(refunded) refunded,SUM(reversed) reversed,
                    AVG(captured-refunded-reversed) average_value FROM customer_values GROUP BY currency ORDER BY currency
                """, (rs, row) -> {
                    String currency = rs.getString("currency").trim();
                    BigDecimal captured = rs.getBigDecimal("captured");
                    BigDecimal refunded = rs.getBigDecimal("refunded");
                    BigDecimal reversed = rs.getBigDecimal("reversed");
                    int digits = Currency.getInstance(currency).getDefaultFractionDigits();
                    if (digits < 0) throw new IllegalStateException("Reporting currency requires a minor-unit policy");
                    return new CustomerLifetimeValue(currency, rs.getLong("customers"), captured, refunded, reversed,
                            captured.subtract(refunded).subtract(reversed), rs.getBigDecimal("average_value").setScale(digits, RoundingMode.HALF_EVEN));
                }, window.end());
        return new AdvancedReport(window.since(), window.until(), variants, lifetime);
    }

    private static Window window(int days) {
        validateDays(days);
        Instant until = Instant.now();
        return new Window(until.minus(days, ChronoUnit.DAYS), until);
    }

    private static void validateDays(int days) {
        if (days < 1 || days > 365) throw new IllegalArgumentException("Report days must be between 1 and 365");
    }

    private record Window(Instant since, Instant until) {
        Timestamp start() { return Timestamp.from(since); }
        Timestamp end() { return Timestamp.from(until); }
    }
    private record Counts(long orders, long customers) { }
    public record StatusCount(String status, long orders) { }
    public record CurrencySales(String currency, long orders, long customers, BigDecimal capturedAmount,
            BigDecimal refundedAmount, BigDecimal reversedAmount, BigDecimal netCapturedAmount) { }
    public record StandardReport(Instant since, Instant until, long orders, long activeCustomers,
            List<StatusCount> ordersByStatus, List<CurrencySales> salesByCurrency) { }
    public record TopVariant(String currency, UUID variantId, String sku, String variantName, UUID productId, String productName, BigDecimal unitsOnCapturedOrders,
            BigDecimal recordedLineValue, int rank) { }
    public record CustomerLifetimeValue(String currency, long customers, BigDecimal capturedAmount,
            BigDecimal refundedAmount, BigDecimal reversedAmount, BigDecimal netCapturedAmount, BigDecimal averageNetCapturedValue) { }
    public record AdvancedReport(Instant since, Instant until, List<TopVariant> topVariants,
            List<CustomerLifetimeValue> customerLifetimeValues) { }
}
