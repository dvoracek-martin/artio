package io.artio.inventory;
public record ReservationStateChanged(ReservationId reservationId,ReservationState state) implements ReservationEvent {}
