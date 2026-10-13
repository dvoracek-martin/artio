package io.artio.api.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuditWebConfiguration implements WebMvcConfigurer {
    private final AuditTrailInterceptor audit;
    public AuditWebConfiguration(AuditTrailInterceptor audit){this.audit=audit;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(audit).addPathPatterns("/api/**");}
}
