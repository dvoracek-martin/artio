package io.artio.inventory;
import io.artio.kernel.AggregateEvent;
public sealed interface ReservationEvent extends AggregateEvent permits ReservationCreated,ReservationStateChanged { ReservationId reservationId(); }
