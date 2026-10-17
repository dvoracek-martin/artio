package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.security.PrivilegedStepUpMfa;
import io.artio.extensions.PluginManifestValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class LocalExtensionArchiveUploadControllerSecurityTests {
    @Test
    void delegatedAdminCannotImportExecutablePluginArchive() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ExtensionInstallationJobOperations jobs = mock(ExtensionInstallationJobOperations.class);
        OciArchiveQuarantineStore quarantine = mock(OciArchiveQuarantineStore.class);
        var controller = new LocalExtensionArchiveUploadController(jdbc, new ObjectMapper(),
                mock(PluginManifestValidator.class), jobs, quarantine, "0.1.0", new PrivilegedStepUpMfa(false));
        Jwt token = Jwt.withTokenValue("test-token").header("alg", "none").claim("mfaVerified", false).build();
        var admin = new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThatThrownBy(() -> controller.upload("test.plugin", new MockMultipartFile("archive", new byte[0]),
                "test-job-0001", admin))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(jdbc, jobs, quarantine);
    }
}
