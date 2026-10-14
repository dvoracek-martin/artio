package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class OrderTimelineControllerTests {
    private final OrderTimelineController controller = new OrderTimelineController(mock(JdbcTemplate.class));

    @Test
    void rejectsBlankAndOversizedStaffNotesBeforeDatabaseAccess() {
        assertThatThrownBy(() -> controller.addStaffNote(UUID.randomUUID(), new OrderTimelineController.NoteRequest(" "), null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.addStaffNote(UUID.randomUUID(), new OrderTimelineController.NoteRequest("x".repeat(2001)), null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
