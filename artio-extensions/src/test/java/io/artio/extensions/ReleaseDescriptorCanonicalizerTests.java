package io.artio.extensions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReleaseDescriptorCanonicalizerTests {
  @Test
  void canonicalizesPropertyOrderAndEquivalentNumbers() {
    byte[] result =
        ReleaseDescriptorCanonicalizer.canonicalBytes("{\"z\":1.0,\"a\":{\"b\":2,\"a\":true}}");

    assertThat(result)
        .containsExactly("{\"a\":{\"a\":true,\"b\":2},\"z\":1}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void requiresByteExactCanonicalUtf8Json() {
    assertThat(ReleaseDescriptorCanonicalizer.isCanonical(
            "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8)))
        .isTrue();
    assertThat(ReleaseDescriptorCanonicalizer.isCanonical(
            "{ \"b\":2,\"a\":1 }".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
    assertThat(ReleaseDescriptorCanonicalizer.isCanonical(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .isFalse();
  }
}
