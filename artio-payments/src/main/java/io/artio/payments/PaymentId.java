package io.artio.payments;

import java.util.UUID;
public record PaymentId(UUID value) { public PaymentId { if (value == null) throw new IllegalArgumentException("Payment id is required"); } }
