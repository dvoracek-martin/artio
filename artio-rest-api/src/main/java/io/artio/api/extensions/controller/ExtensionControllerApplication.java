package io.artio.api.extensions.controller;

import io.artio.api.extensions.DockerRuntimeConfiguration;
import io.artio.api.security.ExtensionCredentialService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Minimal entry point for the privileged Docker lifecycle controller runtime. */
@SpringBootApplication(
        scanBasePackages = "io.artio.api.extensions.controller",
        excludeName = {
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
                "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
                "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
                "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
                "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
                "org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration",
                "org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration"
        })
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
@Import({DockerRuntimeConfiguration.class, ExtensionCredentialService.class})
public class ExtensionControllerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExtensionControllerApplication.class, args);
    }
}
