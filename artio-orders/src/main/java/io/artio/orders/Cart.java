package io.artio.orders;

import io.artio.kernel.Aggregate;
import io.artio.kernel.Quantity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Cart extends Aggregate<CartId> {
    private final UUID userId;
    private final Map<UUID, CartLineItem> items = new LinkedHashMap<>();
    private final Instant createdAt;
    private final Instant expiresAt;

    public Cart(CartId id, UUID userId) {
        super(id);
        if (userId == null) throw new IllegalArgumentException("User id is required");
        this.userId = userId;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(30L * 24 * 60 * 60);
    }

    public void addItem(CartLineItem item) {
        if (Instant.now().isAfter(expiresAt)) throw new IllegalStateException("Cart expired");
        CartLineItem existing = items.get(item.variantId());
        if (existing != null) {
            if (!existing.unitPrice().currency().equals(item.unitPrice().currency())) throw new IllegalArgumentException("Currency mismatch");
            item = new CartLineItem(item.variantId(), new Quantity(existing.quantity().value().add(item.quantity().value())), item.unitPrice());
        }
        items.put(item.variantId(), item);
        recordEvent(new CartItemAdded(getId(), item));
    }

    public void removeItem(UUID variantId) { items.remove(variantId); }
    public UUID userId() { return userId; }
    public List<CartLineItem> items() { return List.copyOf(items.values()); }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
}
