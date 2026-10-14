package io.artio.api.users;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class FirstRunSetupConfiguration {
    @Bean
    ApplicationRunner initializeFirstRunSetup(JdbcTemplate jdbc, Environment environment,
            @Value("${artio.setup.token:}") String configuredToken,
            @Value("${artio.setup.skip:false}") boolean setupSkip) {
        return args -> {
            DevelopmentSetupPolicy.validate(environment, setupSkip);
            boolean administratorExists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM users WHERE role IN ('ADMIN','SUPERADMIN'))", Boolean.class));
            if (setupSkip && !administratorExists) {
                var encoder = new BCryptPasswordEncoder();
                jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,permissions,created_at) VALUES (?,?,?,?,?,'SUPERADMIN','*',now())",
                        java.util.UUID.randomUUID(), "admin@artio.local", encoder.encode("ArtioDevOnly!ChangeMe2026"), "Artio", "Developer");
                jdbc.update("INSERT INTO artio_installation_settings(singleton_id,country_code,currency_code,locale,timezone) VALUES (true,'CH','CHF','de-CH','Europe/Zurich') ON CONFLICT (singleton_id) DO NOTHING");
                return;
            }
            if (administratorExists) {
                jdbc.update("UPDATE artio_first_run_setup SET completed_at=COALESCE(completed_at,now()), updated_at=now() WHERE singleton_id=true");
                return;
            }
            if (configuredToken == null || configuredToken.length() < 32
                    || configuredToken.toLowerCase(java.util.Locale.ROOT).contains("replace")) {
                throw new IllegalStateException("Set ARTIO_SETUP_TOKEN to a unique value of at least 32 characters before starting an uninitialized Artio installation");
            }
            jdbc.update("INSERT INTO artio_first_run_setup(singleton_id,setup_token_sha256) VALUES (true,?) "
                            + "ON CONFLICT (singleton_id) DO UPDATE SET setup_token_sha256=CASE WHEN artio_first_run_setup.completed_at IS NULL THEN EXCLUDED.setup_token_sha256 ELSE artio_first_run_setup.setup_token_sha256 END",
                    sha256(configuredToken));
        };
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
