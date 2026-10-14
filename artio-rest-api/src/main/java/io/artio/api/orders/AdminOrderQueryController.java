package io.artio.api.orders;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only, bounded order search and detail views for authorized Backoffice staff. */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderQueryController {
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AdminOrderQueryController(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @GetMapping
    public OrderPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        if (page < 0 || page > 100_000 || size < 1 || size > MAX_PAGE_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be non-negative and size must be 1–100");
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null && !List.of("PENDING", "CONFIRMED", "PAID", "PARTIALLY_SHIPPED", "SHIPPED", "DELIVERED", "CANCELLED", "RETURNED").contains(normalizedStatus))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported order status filter");
        String search = q == null || q.isBlank() ? null : q.trim();
        if (search != null && search.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query is too long");
        String sortColumn = switch (sort) {
            case "createdAt" -> "o.created_at";
            case "total" -> "o.total";
            case "status" -> "o.status";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported order sort field");
        };
        String sortDirection = switch (direction.toLowerCase(java.util.Locale.ROOT)) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sort direction must be asc or desc");
        };
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> arguments = new ArrayList<>();
        if (normalizedStatus != null) { where.append(" AND o.status=?"); arguments.add(normalizedStatus); }
        if (search != null) {
            where.append(" AND (CAST(o.id AS text) ILIKE ? OR u.email ILIKE ? OR COALESCE(o.tracking_number,'') ILIKE ?)");
            String pattern = "%" + search.replace("%", "\\%").replace("_", "\\_") + "%";
            arguments.add(pattern); arguments.add(pattern); arguments.add(pattern);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM orders o JOIN users u ON u.id=o.user_id" + where,
                Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<OrderSummary> items = jdbc.query("SELECT o.id,o.status,o.created_at,o.user_id,u.email,o.total,o.currency,o.tracking_number,"
                        + "o.shipment_reference,o.shipping_method_id,o.shipping_label_url,o.shipping_country,o.market,o.tax_jurisdiction "
                        + "FROM orders o JOIN users u ON u.id=o.user_id" + where + " ORDER BY " + sortColumn + " " + sortDirection
                        + ",o.id ASC LIMIT ? OFFSET ?",
                (rs, row) -> new OrderSummary(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getBigDecimal("total"), rs.getString("currency").trim(), rs.getString("tracking_number"),
                        rs.getString("shipment_reference"), rs.getString("shipping_method_id"), rs.getString("shipping_label_url") != null,
                        rs.getString("shipping_country") == null ? null : rs.getString("shipping_country").trim(),
                        rs.getString("market"), rs.getString("tax_jurisdiction")), pageArguments.toArray());
        long count = total == null ? 0 : total;
        return new OrderPage(items, page, size, count, (int) Math.ceil((double) count / size));
    }

    @GetMapping("/{id}")
    public OrderDetail detail(@PathVariable UUID id) {
        List<OrderDetail> rows = jdbc.query("SELECT o.id,o.status,o.created_at,o.user_id,u.email,o.subtotal,o.tax,o.shipping,o.total,o.currency,"
                        + "o.shipping_address,o.billing_address,o.tracking_number,o.shipment_reference,o.shipping_method_id,o.shipping_label_url,"
                        + "o.shipping_country,o.market,o.tax_jurisdiction,o.tax_snapshot FROM orders o JOIN users u ON u.id=o.user_id WHERE o.id=?",
                (rs, row) -> new OrderDetail(rs.getObject("id", UUID.class), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax"),
                        rs.getBigDecimal("shipping"), rs.getBigDecimal("total"), rs.getString("currency").trim(), rs.getString("shipping_address"),
                        rs.getString("billing_address"), rs.getString("tracking_number"), rs.getString("shipment_reference"),
                        rs.getString("shipping_method_id"), rs.getString("shipping_label_url") != null,
                        rs.getString("shipping_country") == null ? null : rs.getString("shipping_country").trim(),
                        rs.getString("market"), rs.getString("tax_jurisdiction"), taxSnapshot(rs.getString("tax_snapshot")), null, null), id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        OrderDetail order = rows.getFirst();
        List<OrderLine> lines = jdbc.query("SELECT oi.id,oi.variant_id,oi.bundle_id,oi.quantity,oi.unit_price,oi.currency,COALESCE((SELECT sum(si.quantity) FROM order_shipment_items si JOIN order_shipments s ON s.id=si.shipment_id WHERE s.order_id=oi.order_id AND si.order_item_id=oi.id),0) AS shipped_quantity FROM order_items oi WHERE oi.order_id=? ORDER BY oi.id",
                (rs, row) -> new OrderLine(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getObject("bundle_id", UUID.class), rs.getBigDecimal("quantity"), rs.getBigDecimal("shipped_quantity"), rs.getBigDecimal("unit_price"), rs.getString("currency").trim()), id);
        List<PaymentSummary> payments = jdbc.query("SELECT id,method,status,amount,refunded_amount,currency,provider_reference,created_at,updated_at FROM payments WHERE order_id=? ORDER BY created_at",
                (rs, row) -> new PaymentSummary(rs.getObject("id", UUID.class), rs.getString("method"), rs.getString("status"),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("refunded_amount"), rs.getString("currency").trim(), rs.getString("provider_reference"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id);
        return order.with(lines, payments);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q) {
        OrderPage page = list(0, MAX_PAGE_SIZE, status, q, "createdAt", "desc");
        if (page.totalElements() > MAX_PAGE_SIZE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export is limited to 100 orders; narrow the filters");
        StringBuilder csv = new StringBuilder("order_id,status,created_at,customer_email,total,currency,tracking_number,shipping_method_id\r\n");
        for (OrderSummary order : page.items()) csv.append(csv(order.id().toString())).append(',').append(csv(order.status())).append(',')
                .append(csv(order.createdAt().toString())).append(',').append(csv(order.customerEmail())).append(',')
                .append(csv(order.total().toPlainString())).append(',').append(csv(order.currency())).append(',')
                .append(csv(order.trackingNumber())).append(',').append(csv(order.shippingMethodId())).append("\r\n");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=artio-orders.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(String value) {
        if (value == null) return "\"\"";
        String safe = value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@") ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private JsonNode taxSnapshot(String value) {
        try { return mapper.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Stored order tax snapshot is invalid", invalid);
        }
    }

    public record OrderPage(List<OrderSummary> items, int page, int size, long totalElements, int totalPages) { }
    public record OrderSummary(UUID id, String status, Instant createdAt, UUID userId, String customerEmail,
            java.math.BigDecimal total, String currency, String trackingNumber, String shipmentReference,
            String shippingMethodId, boolean shippingLabelAvailable, String shippingCountry, String market, String taxJurisdiction) { }
    public record OrderDetail(UUID id, String status, Instant createdAt, UUID userId, String customerEmail,
            java.math.BigDecimal subtotal, java.math.BigDecimal tax, java.math.BigDecimal shipping, java.math.BigDecimal total,
            String currency, String shippingAddress, String billingAddress, String trackingNumber, String shipmentReference,
            String shippingMethodId, boolean shippingLabelAvailable, String shippingCountry, String market, String taxJurisdiction,
            JsonNode taxSnapshot, List<OrderLine> lines, List<PaymentSummary> payments) {
        OrderDetail with(List<OrderLine> lines, List<PaymentSummary> payments) {
            return new OrderDetail(id,status,createdAt,userId,customerEmail,subtotal,tax,shipping,total,currency,shippingAddress,billingAddress,
                    trackingNumber,shipmentReference,shippingMethodId,shippingLabelAvailable,shippingCountry,market,taxJurisdiction,taxSnapshot,lines,payments);
        }
    }
    public record OrderLine(UUID id, UUID variantId, UUID bundleId, java.math.BigDecimal quantity, java.math.BigDecimal shippedQuantity, java.math.BigDecimal unitPrice, String currency) { }
    public record PaymentSummary(UUID id, String method, String status, java.math.BigDecimal amount, java.math.BigDecimal refundedAmount, String currency,
            String providerReference, Instant createdAt, Instant updatedAt) { }
}
