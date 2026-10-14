package io.artio.orders;

import io.artio.kernel.Aggregate;
import io.artio.kernel.Money;
import io.artio.kernel.Quantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

public final class Order extends Aggregate<OrderId> {
    private final UUID userId;
    private final List<OrderLineItem> items;
    private final Money subtotal;
    private final Money tax;
    private final Money shipping;
    private final Money total;
    private final OrderStatus status;
    private final String shippingAddress;
    private final String billingAddress;
    private final Instant createdAt;

    private Order(OrderId id, UUID userId, List<OrderLineItem> items, Money subtotal, Money tax,
                  Money shipping, String shippingAddress, String billingAddress, OrderStatus status, Instant createdAt) {
        super(id);
        this.userId = userId;
        this.items = List.copyOf(items);
        this.subtotal = subtotal;
        this.tax = tax;
        this.shipping = shipping;
        this.total = new Money(subtotal.amount().add(tax.amount()).add(shipping.amount()), subtotal.currency());
        this.shippingAddress = shippingAddress;
        this.billingAddress = billingAddress;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Order checkout(Cart cart, String shippingAddress, String billingAddress, BigDecimal taxRate, Money shipping) {
        if (cart.items().isEmpty()) throw new IllegalArgumentException("Cart is empty");
        if (shippingAddress == null || shippingAddress.isBlank()) throw new IllegalArgumentException("Shipping address is required");
        Currency currency = cart.items().getFirst().unitPrice().currency();
        BigDecimal subtotalValue = BigDecimal.ZERO;
        for (var item : cart.items()) {
            if (!currency.equals(item.unitPrice().currency())) throw new IllegalArgumentException("Cart currency mismatch");
            subtotalValue = subtotalValue.add(item.unitPrice().amount().multiply(item.quantity().value()));
        }
        Money subtotal = new Money(subtotalValue, currency);
        Money tax = new Money(subtotalValue.multiply(taxRate).movePointLeft(2), currency);
        if (!currency.equals(shipping.currency())) throw new IllegalArgumentException("Shipping currency mismatch");
        var order = new Order(new OrderId(UUID.randomUUID()), cart.userId(), cart.items().stream().map(i -> new OrderLineItem(i.variantId(), i.quantity(), i.unitPrice())).toList(), subtotal, tax, shipping, shippingAddress, billingAddress, OrderStatus.PENDING, Instant.now());
        order.recordEvent(new OrderCreated(order.getId(), order.userId));
        return order;
    }

    public static Order checkout(Cart cart, String shippingAddress, String billingAddress, Money subtotal, Money tax, Money shipping) {
        if (cart.items().isEmpty()) throw new IllegalArgumentException("Cart is empty");
        if (shippingAddress == null || shippingAddress.isBlank()) throw new IllegalArgumentException("Shipping address is required");
        if (!subtotal.currency().equals(tax.currency()) || !subtotal.currency().equals(shipping.currency()))
            throw new IllegalArgumentException("Order currency mismatch");
        var order = new Order(new OrderId(UUID.randomUUID()), cart.userId(), cart.items().stream()
            .map(i -> new OrderLineItem(i.variantId(), i.quantity(), i.unitPrice())).toList(), subtotal, tax,
                shipping, shippingAddress, billingAddress == null ? shippingAddress : billingAddress,
                OrderStatus.PENDING, Instant.now());
        order.recordEvent(new OrderCreated(order.getId(), order.userId));
        return order;
    }

    public static Order checkout(UUID userId, List<OrderLineItem> items, String shippingAddress, String billingAddress,
                                 Money subtotal, Money tax, Money shipping) {
        return checkout(UUID.randomUUID(), userId, items, shippingAddress, billingAddress, subtotal, tax, shipping);
    }

    public static Order checkout(UUID orderId, UUID userId, List<OrderLineItem> items, String shippingAddress, String billingAddress,
                                 Money subtotal, Money tax, Money shipping) {
        if (items.isEmpty()) throw new IllegalArgumentException("Cart is empty");
        if (shippingAddress == null || shippingAddress.isBlank()) throw new IllegalArgumentException("Shipping address is required");
        if (!subtotal.currency().equals(tax.currency()) || !subtotal.currency().equals(shipping.currency())) throw new IllegalArgumentException("Order currency mismatch");
        var order = new Order(new OrderId(orderId), userId, items, subtotal, tax, shipping, shippingAddress,
                billingAddress == null ? shippingAddress : billingAddress, OrderStatus.PENDING, Instant.now());
        order.recordEvent(new OrderCreated(order.getId(), order.userId));
        return order;
    }

    public UUID userId() { return userId; }
    public List<OrderLineItem> items() { return items; }
    public Money subtotal() { return subtotal; }
    public Money tax() { return tax; }
    public Money shipping() { return shipping; }
    public Money total() { return total; }
    public OrderStatus status() { return status; }
    public String shippingAddress() { return shippingAddress; }
    public String billingAddress() { return billingAddress; }
    public Instant createdAt() { return createdAt; }
}
