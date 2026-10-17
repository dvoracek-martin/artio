package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MediaStorageRepairServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PluginMediaStorage storage = mock(PluginMediaStorage.class);
    private final MediaStorageRepairService service = new MediaStorageRepairService(jdbc, storage);
    private final UUID runId = UUID.randomUUID();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void stopsAtFirstFailureAndKeepsCursorBeforeThatObjectForRetry() {
        String first = UUID.randomUUID() + ".jpg";
        String failed = UUID.randomUUID() + ".png";
        String afterFailure = UUID.randomUUID() + ".png";
        org.mockito.Mockito.doReturn(List.of(first, failed, afterFailure)).when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        doNothing().when(storage).repair(first, runId);
        doThrow(new IllegalStateException("provider down")).when(storage).repair(failed, runId);

        var result = service.repair(null, 50, runId);

        assertThat(result.inspected()).isEqualTo(2);
        assertThat(result.repaired()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.complete()).isFalse();
        assertThat(result.nextCursor()).isEqualTo(first);
        assertThat(result.failures()).containsExactly(new MediaStorageRepairService.Failure(failed, "IllegalStateException"));
        verify(storage, never()).repair(afterFailure, runId);
    }

    @Test
    void rejectsUnboundedBatchesWithoutQueryingStorage() {
        assertThatThrownBy(() -> service.repair(null, 101, runId)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, storage);
    }
}
