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
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
                "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
                "org.springframework.boot.autoconfigure.graphql.GraphQlAutoConfiguration",
                "org.springframework.boot.autoconfigure.graphql.servlet.GraphQlWebMvcAutoConfiguration"
        })
@ConditionalOnProperty(name = "artio.runtime.role", havingValue = "extension-controller")
@Import({DockerRuntimeConfiguration.class, ExtensionCredentialService.class})
public class ExtensionControllerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExtensionControllerApplication.class, args);
    }
}
