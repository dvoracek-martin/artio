package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class MediaStorageStatusControllerTests {
    @Test
    void reportsSafeStorageModeAndRepairCountsWithoutConfigurationSecrets() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductMediaService media = mock(ProductMediaService.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("media_count")).thenReturn(8L);
        when(resultSet.getLong("active_count")).thenReturn(5L);
        when(resultSet.getLong("delete_pending")).thenReturn(2L);
        when(resultSet.getLong("tombstoned")).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenAnswer(invocation ->
                ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);
        when(media.isUploadAvailable()).thenReturn(true);

        var token = Jwt.withTokenValue("test-token").header("alg", "none").claim("role", "SUPERADMIN").build();
        var status = new MediaStorageStatusController(media, jdbc, "plugins", "s3").getStatus(new JwtAuthenticationToken(token));

        assertThat(status.uploadAvailable()).isTrue();
        assertThat(status.storageMode()).isEqualTo("plugins");
        assertThat(status.migrationSource()).isEqualTo("s3");
        assertThat(status.mediaCount()).isEqualTo(8);
        assertThat(status.activePluginObjects()).isEqualTo(5);
        assertThat(status.pendingMigration()).isEqualTo(3);
        assertThat(status.deletePending()).isEqualTo(2);
        assertThat(status.tombstoned()).isEqualTo(1);
        assertThat(status.migrationAvailable()).isTrue();
        assertThat(status.canManageStorage()).isTrue();
    }
}
