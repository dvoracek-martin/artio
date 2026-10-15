package io.artio.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.error.ApiErrorWriter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean SecretKey jwtSecretKey(@Value("${artio.security.jwt-secret}") String secret) {
        SecretConfigurationValidator.requireStrongSecret(secret, "ARTIO_JWT_SECRET");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKey key) { return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${artio.security.allowed-origins:http://localhost:4200,http://localhost:4201}") String origins) {
        CorsConfiguration config=new CorsConfiguration();
        config.setAllowedOrigins(java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key","X-API-Key","X-Artio-Setup-Token"));
        config.setAllowCredentials(false); config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",config); return source;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${artio.security.oauth2-enabled:false}") boolean oauth2Enabled,
            AuthenticationSuccessHandler oauth2SuccessHandler,
            ApiVersioningFilter apiVersioningFilter,
            InstallationSetupGateFilter installationSetupGateFilter,
            PersistedPermissionFilter persistedPermissionFilter,
            ObjectMapper objectMapper) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> java.util.List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'")))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/products/**", "/api/categories/**", "/api/brands/**", "/api/attributes/**", "/api/bundles", "/api/prices/**", "/api/discount-rules/**", "/api/tax-rules/**", "/api/warehouses/**", "/api/stocks/**", "/api/inventory/**",
                        "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**", "/api/v1/attributes/**", "/api/v1/bundles", "/api/v1/prices/**", "/api/v1/discount-rules/**", "/api/v1/tax-rules/**", "/api/v1/warehouses/**", "/api/v1/stocks/**", "/api/v1/inventory/**").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**", "/api/variants/**", "/api/categories/**", "/api/brands/**", "/api/attributes/**", "/api/bundles/**", "/api/prices/**", "/api/discount-rules/**", "/api/tax-rules/**", "/api/warehouses/**", "/api/stocks/**", "/api/inventory/**",
                        "/api/v1/products/**", "/api/v1/variants/**", "/api/v1/categories/**", "/api/v1/brands/**", "/api/v1/attributes/**", "/api/v1/bundles/**", "/api/v1/prices/**", "/api/v1/discount-rules/**", "/api/v1/tax-rules/**", "/api/v1/warehouses/**", "/api/v1/stocks/**", "/api/v1/inventory/**").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**", "/api/categories/**", "/api/brands/**", "/api/attributes/**", "/api/bundles/**", "/api/prices/**", "/api/discount-rules/**", "/api/tax-rules/**", "/api/warehouses/**", "/api/stocks/**", "/api/inventory/**",
                        "/api/v1/products/**", "/api/v1/categories/**", "/api/v1/brands/**", "/api/v1/attributes/**", "/api/v1/bundles/**", "/api/v1/prices/**", "/api/v1/discount-rules/**", "/api/v1/tax-rules/**", "/api/v1/warehouses/**", "/api/v1/stocks/**", "/api/v1/inventory/**").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers(HttpMethod.GET, "/api/warehouses/**", "/api/stock/**", "/api/orders/*/inventory-reservations",
                        "/api/inventory/**", "/api/v1/warehouses/**", "/api/v1/stock/**", "/api/v1/inventory/**", "/api/v1/orders/*/inventory-reservations").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers(HttpMethod.GET, "/api/admin/reports/**", "/api/v1/admin/reports/**")
                        .hasAnyAuthority("ROLE_SUPERADMIN", "PERMISSION_REPORTS_VIEW")
                .requestMatchers("/api/admin/users/**", "/api/v1/admin/users/**")
                        .hasAnyAuthority("ROLE_SUPERADMIN", "PERMISSION_USER_MANAGE")
                .requestMatchers(HttpMethod.GET, "/api/setup/status", "/api/v1/setup/status").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/setup/progress", "/api/setup/complete", "/api/setup/resume", "/api/v1/setup/progress", "/api/v1/setup/complete", "/api/v1/setup/resume",
                        "/api/auth/register", "/api/auth/login", "/api/payments/provider/webhook",
                        "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/payments/provider/webhook").permitAll()
                .requestMatchers("/api/public/**", "/api/v1/public/**", "/actuator/health/**", "/actuator/info",
                        "/api/products/**", "/api/variants/**", "/api/categories/**", "/api/brands/**", "/api/attributes/**", "/api/bundles/**", "/api/pricing/quote",
                        "/api/v1/products/**", "/api/v1/variants/**", "/api/v1/categories/**", "/api/v1/brands/**", "/api/v1/attributes/**", "/api/v1/bundles/**", "/api/v1/pricing/quote").permitAll()
                .requestMatchers("/oauth2/**","/login/oauth2/**").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/api/admin/system/**", "/api/v1/admin/system/**").hasRole("SUPERADMIN")
                .requestMatchers(HttpMethod.POST, "/api/payments/*/refund", "/api/v1/payments/*/refund").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers("/api/admin/**", "/api/v1/admin/**").hasAnyRole("ADMIN", "SUPERADMIN")
                .requestMatchers("/graphql").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(observabilitySessionBearerTokenResolver())
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    ApiErrorWriter.write(objectMapper, request, response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                            "Authentication is required"))
                .accessDeniedHandler((request, response, exception) ->
                    ApiErrorWriter.write(objectMapper, request, response, org.springframework.http.HttpStatus.FORBIDDEN,
                            "Access is denied")))
            .addFilterBefore(installationSetupGateFilter, BearerTokenAuthenticationFilter.class)
            .addFilterBefore(apiVersioningFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new PrivilegedMfaEnrollmentFilter(objectMapper), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(persistedPermissionFilter, PrivilegedMfaEnrollmentFilter.class);
        if(oauth2Enabled)http.oauth2Login(oauth->oauth.successHandler(oauth2SuccessHandler));
        return http.build();
    }

    @Bean
    ApiVersioningFilter apiVersioningFilter() {
        return new ApiVersioningFilter();
    }

    @Bean
    BearerTokenResolver observabilitySessionBearerTokenResolver() {
        var standard = new DefaultBearerTokenResolver();
        return request -> {
            String bearer = standard.resolve(request);
            if (bearer != null) return bearer;
            String path = request.getRequestURI();
            if (path == null || !(path.startsWith("/api/v1/admin/extensions/observability.artio/ui/")
                    || path.equals("/api/v1/admin/extensions/observability.artio/ui"))) return null;
            var cookies = request.getCookies();
            if (cookies == null) return null;
            for (var cookie : cookies) if ("artio_observability_session".equals(cookie.getName())) return cookie.getValue();
            return null;
        };
    }

    @Bean
    InstallationSetupGateFilter installationSetupGateFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new InstallationSetupGateFilter(jdbc, mapper);
    }

    @Bean
    PersistedPermissionFilter persistedPermissionFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new PersistedPermissionFilter(jdbc, mapper);
    }

}
