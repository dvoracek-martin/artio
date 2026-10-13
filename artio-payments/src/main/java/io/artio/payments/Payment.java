package io.artio.payments;

import io.artio.kernel.Aggregate;
import io.artio.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Payment extends Aggregate<PaymentId> {
    private final UUID orderId;
    private final Money amount;
    private final PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;
    private final Instant createdAt;

    private Payment(PaymentId id, UUID orderId, Money amount, PaymentMethod method, PaymentStatus status, String transactionId, Instant createdAt) {
        super(id); this.orderId=Objects.requireNonNull(orderId); this.amount=Objects.requireNonNull(amount); this.method=Objects.requireNonNull(method);
        this.status=Objects.requireNonNull(status); this.transactionId=transactionId; this.createdAt=Objects.requireNonNull(createdAt);
    }
    public static Payment pending(PaymentId id, UUID orderId, Money amount, PaymentMethod method, String transactionId) {
        return new Payment(id,orderId,amount,method,PaymentStatus.PENDING,transactionId,Instant.now());
    }
    public void authorize() { require(PaymentStatus.PENDING); status=PaymentStatus.AUTHORIZED; }
    public void capture() { require(PaymentStatus.AUTHORIZED); status=PaymentStatus.CAPTURED; }
    public void refund() { require(PaymentStatus.CAPTURED); status=PaymentStatus.REFUNDED; }
    private void require(PaymentStatus expected) { if(status!=expected) throw new IllegalStateException("Payment must be " + expected); }
    public UUID orderId(){return orderId;} public Money amount(){return amount;} public PaymentMethod method(){return method;} public PaymentStatus status(){return status;} public String transactionId(){return transactionId;} public Instant createdAt(){return createdAt;}
}
