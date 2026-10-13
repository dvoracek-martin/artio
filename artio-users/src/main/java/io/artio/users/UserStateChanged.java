package io.artio.users;
import io.artio.kernel.AggregateEvent;
public record UserStateChanged(UserId userId,String field,String value) implements AggregateEvent { }
