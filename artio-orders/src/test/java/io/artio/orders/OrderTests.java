package io.artio.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.artio.kernel.Money;
import io.artio.kernel.Quantity;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTests {
    private final Currency usd=Currency.getInstance("USD");
    @Test void cartMergesItemsAndCheckoutCalculatesTaxAndShipping() {
        UUID user=UUID.randomUUID(),variant=UUID.randomUUID();var cart=new Cart(new CartId(UUID.randomUUID()),user);
        cart.addItem(new CartLineItem(variant,new Quantity(BigDecimal.ONE),new Money(new BigDecimal("10.00"),usd)));
        cart.addItem(new CartLineItem(variant,new Quantity(BigDecimal.ONE),new Money(new BigDecimal("10.00"),usd)));
        var order=Order.checkout(cart,"10 Main St","10 Main St",new BigDecimal("10"),new Money(new BigDecimal("2.00"),usd));
        assertThat(cart.items()).hasSize(1);assertThat(cart.items().getFirst().quantity().value()).isEqualByComparingTo("2");
        assertThat(order.subtotal().amount()).isEqualByComparingTo("20.00");assertThat(order.tax().amount()).isEqualByComparingTo("2.0000");assertThat(order.total().amount()).isEqualByComparingTo("24.0000");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    }
    @Test void preventsZeroQuantityAndEmptyCheckout() {
        assertThatThrownBy(()->new CartLineItem(UUID.randomUUID(),new Quantity(BigDecimal.ZERO),new Money(BigDecimal.ONE,usd))).isInstanceOf(IllegalArgumentException.class);
        var cart=new Cart(new CartId(UUID.randomUUID()),UUID.randomUUID());
        assertThatThrownBy(()->Order.checkout(cart,"address","address",BigDecimal.ZERO,new Money(BigDecimal.ZERO,usd))).isInstanceOf(IllegalArgumentException.class);
    }
}
