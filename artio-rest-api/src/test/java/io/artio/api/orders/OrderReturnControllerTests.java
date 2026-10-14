package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.artio.api.inventory.InventoryApplicationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class OrderReturnControllerTests {
    private final OrderReturnController controller = new OrderReturnController(mock(JdbcTemplate.class), mock(InventoryApplicationService.class));

    @Test
    void requiresReasonAndReturnLinesBeforeAccessingOrder() {
        var invalid = new OrderReturnController.ReturnRequest(" ", null, List.of());
        assertThatThrownBy(() -> controller.request(UUID.randomUUID(), "request-1", invalid, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsDuplicateAndNullReturnLines() {
        UUID item = UUID.randomUUID();
        var duplicate = new OrderReturnController.ReturnRequest("damaged", null,
                List.of(new OrderReturnController.ReturnLineRequest(item, 1), new OrderReturnController.ReturnLineRequest(item, 1)));
        assertThatThrownBy(() -> controller.request(UUID.randomUUID(), "request-2", duplicate, null))
                .isInstanceOf(ResponseStatusException.class);
        var nullLine = new OrderReturnController.ReturnRequest("damaged", null, java.util.Arrays.asList((OrderReturnController.ReturnLineRequest) null));
        assertThatThrownBy(() -> controller.request(UUID.randomUUID(), "request-3", nullLine, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enforcesStableIdempotencyKeyBounds() {
        var validLines = List.of(new OrderReturnController.ReturnLineRequest(UUID.randomUUID(), 1));
        var request = new OrderReturnController.ReturnRequest("wrong size", null, validLines);
        assertThatThrownBy(() -> controller.request(UUID.randomUUID(), "x".repeat(129), request, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
