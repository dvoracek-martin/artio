package io.artio.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Base for aggregate roots that collect domain events until they are published. */
public abstract class Aggregate<ID> {
    private final ID id;
    private final List<AggregateEvent> domainEvents = new ArrayList<>();

    protected Aggregate(ID id) {
        this.id = Objects.requireNonNull(id, "Aggregate ID is required");
    }

    public final ID getId() {
        return id;
    }

    protected final void recordEvent(AggregateEvent event) {
        domainEvents.add(Objects.requireNonNull(event, "Domain event is required"));
    }

    public final List<AggregateEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public final void clearDomainEvents() {
        domainEvents.clear();
    }
}
