package io.artio.api.extensions;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@ConditionalOnProperty(prefix = "artio.extensions.docker", name = "mode", havingValue = "controller")
public class DockerRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    DockerClient dockerClient() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).maxConnections(10)
                .connectionTimeout(java.time.Duration.ofSeconds(5))
                .responseTimeout(java.time.Duration.ofSeconds(120)).build();
        return DockerClientImpl.getInstance(config, http);
    }

    @Bean
    DockerExtensionRuntime dockerExtensionRuntime(DockerClient docker,
            @Value("${artio.extensions.docker.network:artio-extensions}") String network,
            @Value("${artio.extensions.docker.port:8080}") int port,
            io.artio.api.security.EncryptionService encryption) {
        return new DockerExtensionRuntime(docker, network, port, encryption);
    }
}
