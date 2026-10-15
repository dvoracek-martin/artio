package io.artio.api.extensions.controller;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
class ExtensionControllerWebConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> controllerWebServerLimits() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> protocol) {
                protocol.setContinueResponseTiming("onRead");
                // Bound draining to two envelope budgets so ordinary overshoots receive 413 instead of a TCP reset.
                protocol.setMaxSwallowSize(2 * ExtensionControllerRequestFilter.MAX_ENVELOPE_BYTES);
            }
        });
    }
}
