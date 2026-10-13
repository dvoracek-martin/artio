package io.artio.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = "io.artio")
@EnableAsync
@EnableCaching
public class ArtioRestApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArtioRestApiApplication.class, args);
    }
}
