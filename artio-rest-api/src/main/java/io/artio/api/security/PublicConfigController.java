package io.artio.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicConfigController {
    private final boolean oauth2Enabled;
    public PublicConfigController(@Value("${artio.security.oauth2-enabled:false}") boolean oauth2Enabled){this.oauth2Enabled=oauth2Enabled;}
    @GetMapping("/api/public/config") public PublicConfig get(){return new PublicConfig(oauth2Enabled);}
    public record PublicConfig(boolean googleOidcEnabled){}
}
