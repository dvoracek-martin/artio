package io.artio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.artio.api.catalog.media.LocalMediaStorage;
import io.artio.api.catalog.media.ProductMediaService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** Destructive operations are confined to fresh labelled fixture volumes and fresh PostgreSQL containers. */
@Testcontainers(disabledWithoutDocker = true)
class BackupRecoveryIntegrationTests {
    private static final String AES_KEY = "recovery-fixture-only-aes-key-material-at-least-32-bytes";
    private static final String AUTH_KEY = "recovery-fixture-only-extension-key-at-least-32-bytes";
    private static final String DB_PASSWORD = "recovery-fixture-only-database-password";
    @TempDir Path temporary;

    @Test
    void restoresCustomDatabaseDumpWithMatchingSecretAndMediaArchivesOnFreshPostgres() throws Exception {
        var docker = DockerClientFactory.instance().client();
        String identity = UUID.randomUUID().toString().replace("-", "");
        List<String> roles = List.of("source-secrets", "source-media", "restored-secrets", "restored-media", "backup");
        var volumes = new java.util.LinkedHashMap<String, String>();
        try {
            for (String role : roles) {
                String name = "artio-recovery-it-" + identity + "-" + role;
                docker.createVolumeCmd().withName(name).withLabels(Map.of("io.artio.test.fixture", identity)).exec();
                volumes.put(role, name);
            }
            try (var archive = new GenericContainer<>("alpine:3.20")
                    .withCreateContainerCmdModifier(command -> command.getHostConfig().withBinds(
                            volumes.entrySet().stream().map(entry -> new Bind(entry.getValue(),
                                    new Volume("/" + entry.getKey()))).toArray(Bind[]::new)))
                    .withCommand("tail", "-f", "/dev/null")) {
                archive.start();
                write(archive, "/source-secrets/encryption-key", AES_KEY);
                write(archive, "/source-secrets/extension-auth-key", AUTH_KEY);
                write(archive, "/source-secrets/database-password", DB_PASSWORD);
                write(archive, "/source-secrets/jwt-secret", "recovery-fixture-only-jwt-key-material-at-least-32-bytes");
                write(archive, "/source-secrets/setup-token", "recovery-fixture-only-setup-token-at-least-32-bytes");
                write(archive, "/source-secrets/extension-controller-token", "recovery-fixture-only-controller-token-at-least-32-bytes");
                shell(archive, "chmod 700 /source-secrets; chmod 600 /source-secrets/*");
                UUID product = UUID.randomUUID();
                UUID media = UUID.randomUUID();
                UUID user = UUID.randomUUID();
                String mediaKey = media + ".png";
                byte[] image;
                try (var output = new java.io.ByteArrayOutputStream()) {
                    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                            java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
                    image = output.toByteArray();
                }
                archive.copyFileToContainer(Transferable.of(image, 0644), "/source-media/" + mediaKey);
                var encryption = new EncryptionService(AES_KEY);
                var mapper = new ObjectMapper();
                Path dump = temporary.resolve("artio-postgres.dump");
                try (var source = postgres(volumes.get("source-secrets"))) {
                    source.start();
                    Flyway.configure().dataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword())
                            .locations("classpath:db/migration").load().migrate();
                    var jdbc = jdbc(source);
                    jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,created_at) VALUES (?,?,'fixture-hash','Recovery','Fixture','SUPERADMIN',now())",
                            user, "recovery-" + identity + "@example.test");
                    jdbc.update("INSERT INTO mfa_methods(user_id,encrypted_secret,verified) VALUES (?,?,true)", user, encryption.encrypt("JBSWY3DPEHPK3PXP"));
                    jdbc.update("INSERT INTO extension_plugins(plugin_id,manifest,image_reference,lifecycle_state,encrypted_secrets) VALUES ('recovery.fixture','{}','fixture@sha256:test','DISABLED',?::jsonb)",
                            mapper.writeValueAsString(Map.of("apiKey", encryption.encrypt("restored-provider-secret"))));
                    jdbc.update("INSERT INTO kafka_consumer_dead_letters(id,source_topic,source_partition,source_offset,encrypted_payload,error_class) VALUES (?,'recovery.fixture',0,1,?,'FixtureFailure')",
                            UUID.randomUUID(), encryption.encrypt("{\"event\":\"recoverable\"}"));
                    jdbc.update("INSERT INTO catalog_product(id,name,sku,active,created_at,updated_at) VALUES (?,'Recovery fixture','RECOVERY-FIXTURE',true,now(),now())", product);
                    jdbc.update("INSERT INTO catalog_product_media(id,product_id,storage_key,content_type,width,height,alt_text,position) VALUES (?,?,?,'image/png',2,2,'Recovery image',0)", media, product, mediaKey);
                    exec(source, "pg_dump", "-U", source.getUsername(), "-d", source.getDatabaseName(), "-Fc", "-f", "/tmp/artio-postgres.dump");
                    source.copyFileFromContainer("/tmp/artio-postgres.dump", dump.toString());
                    shell(archive, "tar czf /backup/secrets.tar.gz -C /source-secrets .; tar czf /backup/media.tar.gz -C /source-media .; cd /backup; sha256sum secrets.tar.gz media.tar.gz > checksums; sha256sum -c checksums");
                }
                // The original database is gone. Restore secrets before first initialization on the new host.
                shell(archive, "cd /backup; sha256sum -c checksums; tar xzf secrets.tar.gz -C /restored-secrets; tar xzf media.tar.gz -C /restored-media; chmod 700 /restored-secrets; chmod 600 /restored-secrets/*");
                String restoredKey = read(archive, "/restored-secrets/encryption-key");
                String dumpChecksum = java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
                var restoredEncryption = new EncryptionService(restoredKey);
                String restoredAuth = read(archive, "/restored-secrets/extension-auth-key");
                assertThat(new ExtensionCredentialService(restoredAuth).derive("recovery.fixture", 3))
                        .isEqualTo(new ExtensionCredentialService(AUTH_KEY).derive("recovery.fixture", 3));
                assertThat(shell(archive, "stat -c '%a' /restored-secrets/encryption-key").trim()).isEqualTo("600");
                try (var restored = postgres(volumes.get("restored-secrets"))) {
                    restored.start();
                    restored.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/artio-postgres.dump");
                    assertThat(exec(restored, "sha256sum", "/tmp/artio-postgres.dump")).startsWith(dumpChecksum + " ");
                    exec(restored, "pg_restore", "-U", restored.getUsername(), "-d", restored.getDatabaseName(),
                            "--exit-on-error", "--no-owner", "--no-privileges", "/tmp/artio-postgres.dump");
                    var jdbc = jdbc(restored);
                    assertThat(Flyway.configure().dataSource(restored.getJdbcUrl(), restored.getUsername(), restored.getPassword())
                            .locations("classpath:db/migration").load().validateWithResult().validationSuccessful).isTrue();
                    String mfa = jdbc.queryForObject("SELECT encrypted_secret FROM mfa_methods WHERE user_id=?", String.class, user);
                    assertThat(restoredEncryption.decrypt(mfa)).isEqualTo("JBSWY3DPEHPK3PXP");
                    String provider = jdbc.queryForObject("SELECT encrypted_secrets->>'apiKey' FROM extension_plugins WHERE plugin_id='recovery.fixture'", String.class);
                    assertThat(restoredEncryption.decrypt(provider)).isEqualTo("restored-provider-secret");
                    String event = jdbc.queryForObject("SELECT encrypted_payload FROM kafka_consumer_dead_letters WHERE source_topic='recovery.fixture'", String.class);
                    assertThat(restoredEncryption.decrypt(event)).isEqualTo("{\"event\":\"recoverable\"}");
                    assertThatThrownBy(() -> new EncryptionService("incorrect-recovery-fixture-key-at-least-32-bytes").decrypt(provider))
                            .isInstanceOf(IllegalArgumentException.class);
                    Path restoredMedia = Files.createDirectory(temporary.resolve("restored-media"));
                    archive.copyFileFromContainer("/restored-media/" + mediaKey, restoredMedia.resolve(mediaKey).toString());
                    var mediaService = new ProductMediaService(jdbc, new LocalMediaStorage(restoredMedia.toString()));
                    assertThat(mediaService.content(product, media).bytes()).containsExactly(image);
                    assertThat(mediaService.findByProduct(product)).hasSize(1);
                }
            }
        } finally {
            for (String name : volumes.values()) {
                assertThat(docker.inspectVolumeCmd(name).exec().getLabels()).containsEntry("io.artio.test.fixture", identity);
                docker.removeVolumeCmd(name).exec();
            }
        }
    }

    private static PostgreSQLContainer<?> postgres(String secretsVolume) {
        return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("artio").withUsername("artio")
                .withPassword(DB_PASSWORD).withEnv("POSTGRES_PASSWORD", "")
                .withEnv("POSTGRES_PASSWORD_FILE", "/run/artio-secrets/database-password")
                .withCreateContainerCmdModifier(command -> {
                    // PostgreSQLContainer.configure adds the password env late; exercise the file-only production path.
                    command.withEnv(java.util.Arrays.stream(command.getEnv())
                            .filter(value -> !value.startsWith("POSTGRES_PASSWORD=")).toList());
                    command.getHostConfig().withBinds(new Bind(secretsVolume, new Volume("/run/artio-secrets"),
                            com.github.dockerjava.api.model.AccessMode.ro));
                });
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer<?> postgres) {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static void write(GenericContainer<?> container, String path, String value) {
        container.copyFileToContainer(Transferable.of((value + "\n").getBytes(StandardCharsets.UTF_8), 0600), path);
    }

    private static String read(GenericContainer<?> container, String path) throws Exception {
        return exec(container, "cat", path).trim();
    }

    private static String shell(GenericContainer<?> container, String script) throws Exception {
        return exec(container, "sh", "-ec", script);
    }

    private static String exec(GenericContainer<?> container, String... command) throws Exception {
        var result = container.execInContainer(command);
        // Never print stdout: key reads are deliberately secret-bearing.
        assertThat(result.getExitCode()).as("Isolated recovery fixture command succeeded").isZero();
        return result.getStdout();
    }
}
