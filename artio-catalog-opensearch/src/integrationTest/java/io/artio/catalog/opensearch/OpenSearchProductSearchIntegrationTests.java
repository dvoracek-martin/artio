package io.artio.catalog.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.artio.catalog.Product;
import io.artio.catalog.ProductDescription;
import io.artio.catalog.ProductId;
import io.artio.catalog.ProductName;
import io.artio.catalog.Sku;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class OpenSearchProductSearchIntegrationTests {
    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>(DockerImageName.parse("opensearchproject/opensearch:2.17.1"))
            .withEnv("discovery.type", "single-node")
            .withEnv("plugins.security.disabled", "true")
            .withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "Artio-Test-OpenSearch-2026!Strong")
            .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forPort(9200).forStatusCode(200).withStartupTimeout(java.time.Duration.ofMinutes(2)));

    @Test
    void rebuildAtomicallyReplacesDocumentsAndKeepsLiveIndexOnReplayFailure() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of("artio.search.url",
                "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200))));
        var search = new OpenSearchProductSearch(RestClient.builder(), environment);
        Product oldProduct = product("Legacy Coffee", "OLD-1");
        Product currentProduct = product("Current Tea", "NEW-1");

        search.rebuild(generation -> {
            generation.index(oldProduct);
            return null;
        });
        assertThat(search.search("Legacy", 10)).extracting(result -> result.id())
                .containsExactly(oldProduct.getId().value());

        search.rebuild(generation -> {
            generation.index(currentProduct);
            return null;
        });
        assertThat(search.search("Legacy", 10)).isEmpty();
        assertThat(search.search("Current", 10)).extracting(result -> result.id())
                .containsExactly(currentProduct.getId().value());

        assertThatThrownBy(() -> search.rebuild(generation -> {
            generation.index(product("Incomplete", "PARTIAL-1"));
            throw new IllegalStateException("source replay failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("source replay failed");
        assertThat(search.search("Current", 10)).extracting(result -> result.id())
                .containsExactly(currentProduct.getId().value());
    }

    private static Product product(String name, String sku) {
        return Product.create(new ProductId(UUID.randomUUID()), new ProductName(name),
                new ProductDescription("Product used to verify generation replacement"), new Sku(sku));
    }
}
