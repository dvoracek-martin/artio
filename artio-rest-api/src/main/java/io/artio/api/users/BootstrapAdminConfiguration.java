package io.artio.api.users;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class BootstrapAdminConfiguration {
    @Bean ApplicationRunner bootstrapAdmin(JdbcTemplate jdbc, Environment environment) {
        return args -> {
            String email = environment.getProperty("ARTIO_BOOTSTRAP_ADMIN_EMAIL");
            String password = environment.getProperty("ARTIO_BOOTSTRAP_ADMIN_PASSWORD");
            boolean noEmail = email == null || email.isBlank();
            boolean noPassword = password == null || password.isBlank();
            if (noEmail && noPassword) return;
            if (noEmail || noPassword || password.length() < 16)
                throw new IllegalStateException("Set both ARTIO_BOOTSTRAP_ADMIN_EMAIL and ARTIO_BOOTSTRAP_ADMIN_PASSWORD (minimum 16 characters)");
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email.toLowerCase());
            if (exists != null && exists == 0) {
                java.util.UUID id=java.util.UUID.randomUUID();
                jdbc.update("INSERT INTO users(id,email,password_hash,first_name,last_name,role,permissions,created_at) VALUES (?,?,?,?,?,'SUPERADMIN','*',now())",
                        id, email.toLowerCase(), new BCryptPasswordEncoder().encode(password), "Artio", "Administrator");
            }
        };
    }
}
