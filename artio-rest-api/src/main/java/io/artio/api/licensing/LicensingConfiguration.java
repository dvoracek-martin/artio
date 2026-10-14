package io.artio.api.licensing;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class LicensingConfiguration {
    @Bean
    @DependsOnDatabaseInitialization
    LicensingService licensingService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Value("${artio.licensing.public-keys-file:}") String keysFile,
            @Value("${artio.licensing.refresh-url:}") String refreshUrl,
            @Value("${artio.licensing.refresh-credential-file:}") String credentialFile) {
        Map<String, java.security.PublicKey> keys;
        boolean valid = true;
        try { keys = LicenseVerificationKeys.load(keysFile); }
        catch (IllegalArgumentException invalid) { keys = Map.of(); valid = false; }
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();
        return new LicensingService(jdbc, new TransactionTemplate(transactions), keys, valid, Clock.systemUTC(),
                new LicenseRefreshClient(http, Duration.ofSeconds(5)), refreshUrl, credentialFile);
    }
}
