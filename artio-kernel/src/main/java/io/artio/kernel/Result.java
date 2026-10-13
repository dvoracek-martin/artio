package io.artio.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A value that is either successful or contains a failure, for explicit error composition. */
public sealed interface Result<T> permits Result.Success, Result.Failure {
    static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    static <T> Result<T> failure(Throwable error) {
        return new Failure<>(error);
    }

    default boolean isSuccess() {
        return this instanceof Success<?>;
    }

    default Optional<T> valueOptional() {
        if (this instanceof Success<T> success) {
            return Optional.of(success.value());
        }
        return Optional.empty();
    }

    default Optional<Throwable> errorOptional() {
        if (this instanceof Failure<T> failure) {
            return Optional.of(failure.error());
        }
        return Optional.empty();
    }

    default <U> Result<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "Mapper is required");
        if (this instanceof Success<T> success) {
            return Result.success(mapper.apply(success.value()));
        }
        return Result.failure(((Failure<T>) this).error());
    }

    default <U> Result<U> flatMap(Function<? super T, ? extends Result<U>> mapper) {
        Objects.requireNonNull(mapper, "Mapper is required");
        if (this instanceof Success<T> success) {
            return Objects.requireNonNull(mapper.apply(success.value()), "Mapped result is required");
        }
        return Result.failure(((Failure<T>) this).error());
    }

    default Result<T> mapError(Function<? super Throwable, ? extends Throwable> mapper) {
        Objects.requireNonNull(mapper, "Error mapper is required");
        if (this instanceof Failure<T> failure) {
            return Result.failure(Objects.requireNonNull(mapper.apply(failure.error()), "Mapped error is required"));
        }
        return this;
    }

    record Success<T>(T value) implements Result<T> {
        public Success {
            Objects.requireNonNull(value, "Successful result value is required");
        }
    }

    record Failure<T>(Throwable error) implements Result<T> {
        public Failure {
            Objects.requireNonNull(error, "Failure cause is required");
        }
    }
}