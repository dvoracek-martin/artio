package io.artio.api.security;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicConfigController {
    private final boolean oauth2Enabled;
    private final JdbcTemplate jdbc;
    public PublicConfigController(@Value("${artio.security.oauth2-enabled:false}") boolean oauth2Enabled, JdbcTemplate jdbc){this.oauth2Enabled=oauth2Enabled;this.jdbc=jdbc;}
    @GetMapping("/api/public/config") public PublicConfig get(){
        var settings=jdbc.query("SELECT country_code,currency_code,locale FROM artio_installation_settings WHERE singleton_id=true",
                (rs,row)->new PublicStoreSettings(rs.getString(1).trim(),rs.getString(2).trim(),rs.getString(3)));
        PublicStoreSettings store=settings.stream().findFirst().orElse(new PublicStoreSettings("CH","CHF","de-CH"));
        return new PublicConfig(oauth2Enabled,store.countryCode(),store.currencyCode(),store.locale());
    }
    public record PublicConfig(boolean googleOidcEnabled,String countryCode,String currencyCode,String locale){}
    private record PublicStoreSettings(String countryCode,String currencyCode,String locale){}
}
