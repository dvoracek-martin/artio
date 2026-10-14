package io.artio.api.catalog.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProductMediaServiceTests {
    @Test
    void acceptsAndReencodesOnlySupportedImages() throws Exception {
        byte[] png = image("png", 3, 2);

        var sanitized = ProductMediaService.sanitize(png);

        assertThat(sanitized.contentType()).isEqualTo("image/png");
        assertThat(sanitized.width()).isEqualTo(3);
        assertThat(sanitized.height()).isEqualTo(2);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(sanitized.content()))).isNotNull();
        assertThatThrownBy(() -> ProductMediaService.sanitize("<svg onload='alert(1)'></svg>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedDimensionsBeforeDecodingTheImage() throws Exception {
        byte[] wide = image("png", 10_001, 1);

        assertThatThrownBy(() -> ProductMediaService.sanitize(wide))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void localStorageUsesOpaqueKeysAndSupportsDurableReadAndDelete() throws Exception {
        var root = Files.createTempDirectory("artio-media-test-");
        try {
            var storage = new LocalMediaStorage(root.toString());
            String key = UUID.randomUUID() + ".png";
            byte[] content = image("png", 1, 1);
            storage.store(key, content);

            assertThat(storage.read(key)).containsExactly(content);
            assertThatThrownBy(() -> storage.read("..\\outside.png")).isInstanceOf(IllegalArgumentException.class);
            storage.delete(key);
            assertThat(Files.exists(root.resolve(key))).isFalse();
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }
}
