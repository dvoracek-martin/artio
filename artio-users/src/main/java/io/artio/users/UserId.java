package io.artio.users;

import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {
    public UserId { Objects.requireNonNull(value, "User id is required"); }
}
