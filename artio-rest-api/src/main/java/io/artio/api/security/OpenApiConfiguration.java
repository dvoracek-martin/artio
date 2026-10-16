package io.artio.api.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the current REST contract and its actual anonymous/authenticated route boundary. */
@Configuration
public class OpenApiConfiguration {
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI artioOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Artio Core API")
                        .version("0.1.0")
                        .description("Generated REST contract for Artio Core API v1. All REST routes use /api/v1."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer bearerSecurityRequirements() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().forEach((path, pathItem) -> {
                pathItem.readOperationsMap().forEach((method, operation) -> {
                    if (!isPublic(path, method)) {
                        operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
                    }
                });
            });
        };
    }

    private static boolean isPublic(String path, PathItem.HttpMethod method) {
        if ("/graphql".equals(path)) return true;
        if (method == PathItem.HttpMethod.POST
                && ("/api/v1/auth/register".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/payments/provider/webhook".equals(path)
                || "/api/v1/pricing/quote".equals(path))) return true;
        if (path.startsWith("/api/v1/public/")) return true;
        return method == PathItem.HttpMethod.GET
                && (path.equals("/api/v1/products") || path.startsWith("/api/v1/products/")
                || path.equals("/api/v1/categories") || path.startsWith("/api/v1/categories/")
                || path.equals("/api/v1/brands") || path.startsWith("/api/v1/brands/")
                || path.equals("/api/v1/attributes") || path.startsWith("/api/v1/attributes/")
                || path.equals("/api/v1/bundles") || path.startsWith("/api/v1/bundles/"));
    }
}
