package io.artio.users;

import io.artio.kernel.AggregateEvent;

public record UserRegistered(UserId userId, Email email) implements AggregateEvent { }
