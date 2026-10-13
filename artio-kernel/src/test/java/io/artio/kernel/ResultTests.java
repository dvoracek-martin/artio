package io.artio.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResultTests {
    @Test
    void mapsAndFlatMapsSuccessfulValues() {
        var result = Result.success("42")
                .map(Integer::parseInt)
                .flatMap(value -> Result.success(value * 2));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.valueOptional()).contains(84);
        assertThat(result.errorOptional()).isEmpty();
    }

    @Test
    void failuresSkipValueMappers() {
        var failure = new IllegalArgumentException("bad input");
        var result = Result.<String>failure(failure)
                .map(String::length)
                .flatMap(value -> Result.success(value.toString()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.valueOptional()).isEmpty();
        assertThat(result.errorOptional()).containsSame(failure);
    }

    @Test
    void mapsFailureWithoutChangingSuccess() {
        var original = new IllegalArgumentException("bad input");
        var mapped = Result.<String>failure(original).mapError(error -> new IllegalStateException(error.getMessage(), error));
        var success = Result.success("ok").mapError(error -> new IllegalStateException(error.getMessage(), error));

        var mappedError = (IllegalStateException) mapped.errorOptional().orElseThrow();
        assertThat(mappedError).hasMessage("bad input").hasCause(original);
        assertThat(success.valueOptional()).contains("ok");
    }

    @Test
    void rejectsNullResultValuesAndErrors() {
        assertThatThrownBy(() -> Result.success(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Result.failure(null)).isInstanceOf(NullPointerException.class);
    }
}