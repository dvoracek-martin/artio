package io.artio.api.extensions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class OciArchiveQuarantineStoreTests {
  @TempDir Path temporaryDirectory;

  @Test
  void streamsUploadIntoPrivateQuarantineAndComputesSha256() throws Exception {
    var store = new OciArchiveQuarantineStore(temporaryDirectory.toString(), 16);
    var upload = new MockMultipartFile("archive", "customer.tar", "application/x-tar", new byte[] {1, 2, 3});

    var stored = store.store(upload);

    assertThat(Files.readAllBytes(stored.path())).containsExactly(1, 2, 3);
    assertThat(stored.size()).isEqualTo(3);
    assertThat(stored.sha256()).isEqualTo("sha256:039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81");
    assertThat(store.pathFor(stored.path().getFileName().toString())).isEqualTo(stored.path());
    store.delete(stored.path());
    assertThat(Files.exists(stored.path())).isFalse();
  }

  @Test
  void enforcesConfiguredMaximumAndRejectsForeignDeletePaths() {
    var store = new OciArchiveQuarantineStore(temporaryDirectory.toString(), 2);
    var upload = new MockMultipartFile("archive", "customer.tar", "application/x-tar", new byte[] {1, 2, 3});

    assertThatThrownBy(() -> store.store(upload)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("upload limit");
    assertThatThrownBy(() -> store.delete(temporaryDirectory.getParent().resolve("outside.tar")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
