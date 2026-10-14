package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminOrderQueryControllerTests {
    private final AdminOrderQueryController controller = new AdminOrderQueryController(mock(JdbcTemplate.class), new ObjectMapper());

    @Test
    void boundsPageSizeAndPageOffsetBeforeQueryingDatabase() {
        assertThatThrownBy(() -> controller.list(-1, 25, null, null, "createdAt", "desc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 101, null, null, "createdAt", "desc"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void acceptsOnlyKnownStatusesSortFieldsAndDirections() {
        assertThatThrownBy(() -> controller.list(0, 25, "UNKNOWN", null, "createdAt", "desc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 25, null, null, "email", "desc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 25, null, null, "createdAt", "sideways"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsOversizedSearchInput() {
        assertThatThrownBy(() -> controller.list(0, 25, null, "x".repeat(201), "createdAt", "desc"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
