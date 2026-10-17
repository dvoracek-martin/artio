package io.artio.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SchemaSnapshotPolicyTests {
    @Test
    void keepsExactlyOneDevelopmentSchemaSnapshot() throws IOException, URISyntaxException {
        var migrationDirectory = Path.of(Objects.requireNonNull(
                getClass().getClassLoader().getResource("db/migration"), "Flyway migration directory must exist")
                .toURI());

        try (var entries = Files.list(migrationDirectory)) {
            assertThat(entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList())
                    .containsExactly("V1__initial_schema.sql");
        }
    }
}
