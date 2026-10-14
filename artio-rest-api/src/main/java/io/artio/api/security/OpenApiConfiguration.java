package io.artio.api.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.ArrayList;
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
                        .description("Generated REST contract for Artio Core API v1. Use /api/v1 paths. "
                                + "Unversioned /api routes remain compatibility aliases."))
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
            var versionedPaths = new ArrayList<java.util.Map.Entry<String, PathItem>>();
            openApi.getPaths().forEach((path, pathItem) -> {
                if ("/api".equals(path)) {
                    versionedPaths.add(java.util.Map.entry("/api/v1", pathItem));
                } else if (path.startsWith("/api/")) {
                    versionedPaths.add(java.util.Map.entry("/api/v1" + path.substring("/api".length()), pathItem));
                }
            });
            versionedPaths.forEach(entry -> openApi.getPaths().addPathItem(entry.getKey(), entry.getValue()));
        };
    }

    private static boolean isPublic(String path, PathItem.HttpMethod method) {
        if ("/graphql".equals(path)) return true;
        if (method == PathItem.HttpMethod.POST
                && ("/api/auth/register".equals(path)
                || "/api/auth/login".equals(path)
                || "/api/payments/provider/webhook".equals(path)
                || "/api/pricing/quote".equals(path))) return true;
        if (path.startsWith("/api/public/")) return true;
        return method == PathItem.HttpMethod.GET
                && (path.equals("/api/products") || path.startsWith("/api/products/")
                || path.equals("/api/categories") || path.startsWith("/api/categories/")
                || path.equals("/api/brands") || path.startsWith("/api/brands/")
                || path.equals("/api/attributes") || path.startsWith("/api/attributes/")
                || path.equals("/api/bundles") || path.startsWith("/api/bundles/"));
    }
}
