package io.artio.api.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AdminCatalogProductQueryControllerTests {
    private final AdminCatalogProductQueryController controller = new AdminCatalogProductQueryController(mock(JdbcTemplate.class));

    @Test void rejectsUnboundedPagingBeforeDatabaseAccess() {
        assertThatThrownBy(() -> controller.list(-1, 25, null, "ALL", null, null, "name", "asc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 101, null, "ALL", null, null, "name", "asc"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void acceptsOnlyKnownStateSortAndDirectionValues() {
        assertThatThrownBy(() -> controller.list(0, 25, null, "REMOVED", null, null, "name", "asc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 25, null, "ALL", null, null, "description", "asc"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.list(0, 25, null, "ALL", null, null, "name", "sideways"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test void boundsSearchTextBeforeDatabaseAccess() {
        assertThatThrownBy(() -> controller.list(0, 25, "x".repeat(201), "ALL", null, null, "name", "asc"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
