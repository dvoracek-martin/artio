package io.artio.api.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only operator queue for verified captures that need explicit financial resolution. */
@RestController
@RequestMapping("/api/v1/admin/payments/reconciliation-cases")
public class PaymentReconciliationCaseController {
    private final JdbcTemplate jdbc;

    public PaymentReconciliationCaseController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public List<PaymentReconciliationCaseView> list(JwtAuthenticationToken auth) {
        String role = auth.getToken().getClaimAsString("role");
        if (role == null || !Set.of("ADMIN", "SUPERADMIN").contains(role))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return jdbc.query("SELECT case_id,payment_id,order_id,case_type,expected_amount,captured_amount,expected_currency,captured_currency,status,created_at "
                        + "FROM payment_reconciliation_cases WHERE status='OPEN' ORDER BY created_at LIMIT 200",
                (rs, row) -> new PaymentReconciliationCaseView(rs.getObject("case_id", UUID.class),
                        rs.getObject("payment_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("case_type"),
                        rs.getBigDecimal("expected_amount"), rs.getBigDecimal("captured_amount"),
                        rs.getString("expected_currency").trim(), rs.getString("captured_currency").trim(), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public record PaymentReconciliationCaseView(UUID caseId, UUID paymentId, UUID orderId, String type,
            BigDecimal expectedAmount, BigDecimal capturedAmount, String expectedCurrency, String capturedCurrency,
            String status, Instant createdAt) { }
}
