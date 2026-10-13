package io.artio.users;

import java.util.Locale;

public record Email(String value) {
    public Email {
        if (value == null || value.length() > 255 || !value.matches("^[A-Za-z0-9+_.-]+@([A-Za-z0-9-]+\\.)+[A-Za-z]{2,}$"))
            throw new IllegalArgumentException("Invalid email");
        value = value.toLowerCase(Locale.ROOT);
    }
}
