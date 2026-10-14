package io.artio.api.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.artio.api.bundles.BundleApplicationService;
import io.artio.api.inventory.InventoryApplicationService;
import io.artio.api.pricing.PricingApplicationService;
import io.artio.extensions.commerce.ShippingProvider;
import io.artio.extensions.commerce.ShippingProvider.Address;
import io.artio.extensions.commerce.ShippingProvider.ShippingCapabilities;
import io.artio.extensions.commerce.ShippingProvider.ShippingMethod;
import io.artio.extensions.commerce.ShippingProvider.ShippingQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CartControllerShippingTests {
    private final ShippingProvider provider=mock(ShippingProvider.class);
    private final ObjectProvider<ShippingProvider> providerRef=mock(ObjectProvider.class);
    private final CartController controller=new CartController(mock(JdbcTemplate.class),mock(PricingApplicationService.class),
            mock(InventoryApplicationService.class),mock(BundleApplicationService.class),new ObjectMapper(),new BigDecimal("4.50"),
            mock(ObjectProvider.class),providerRef);
    private final UUID userId=UUID.randomUUID();

    @Test
    void discoversAndQuotesOnlyMethodsOfferedForTheDestination() {
        when(providerRef.getIfAvailable()).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new ShippingCapabilities(true,true,true,false,List.of("CH")));
        when(provider.methods(any(),org.mockito.ArgumentMatchers.eq("CHF"))).thenReturn(List.of(
                new ShippingMethod("swiss-post-pri","PostPac Priority","Merchant configured",Map.of())));
        when(provider.quote(any())).thenReturn(new ShippingQuote("swiss-post-pri",new BigDecimal("9.50"),"CHF","Merchant configured"));
        var destination=new Address("ch","8000","Zürich","Main 1",null,"Ada Example");

        var methods=controller.shippingMethods("ch","8000","Zürich","Main 1","Ada Example");
        var quote=controller.shippingQuote(new CartController.ShippingQuoteRequest("swiss-post-pri",destination,BigDecimal.ONE,new BigDecimal("80.00")),auth());

        assertThat(methods).extracting(CartController.ShippingMethodView::methodId).containsExactly("swiss-post-pri");
        assertThat(quote.amount()).isEqualByComparingTo("9.50");
        verify(provider).quote(org.mockito.ArgumentMatchers.argThat(request -> request.destination().countryCode().equals("CH")
                && request.orderId().equals("cart:"+userId) && request.methodId().equals("swiss-post-pri")));
    }

    @Test
    void rejectsProviderQuoteWithWrongCurrency() {
        when(providerRef.getIfAvailable()).thenReturn(provider);
        when(provider.capabilities()).thenReturn(new ShippingCapabilities(true,true,true,false,List.of("CH")));
        when(provider.methods(any(),org.mockito.ArgumentMatchers.eq("CHF"))).thenReturn(List.of(new ShippingMethod("eco","Economy",null,Map.of())));
        when(provider.quote(any())).thenReturn(new ShippingQuote("eco",BigDecimal.ONE,"EUR",null));

        assertThatThrownBy(() -> controller.shippingQuote(new CartController.ShippingQuoteRequest("eco",
                new Address("CH","8000","Zürich","Main 1",null,"Ada"),BigDecimal.ONE,BigDecimal.TEN),auth()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid method, amount or currency");
    }

    @Test
    void retainsConfiguredFlatShippingWhenNoPluginIsSelected() {
        when(providerRef.getIfAvailable()).thenReturn(null);
        var quote=controller.shippingQuote(new CartController.ShippingQuoteRequest("standard",
                new Address("CH","8000","Zürich","Main 1",null,"Ada"),BigDecimal.ONE,BigDecimal.TEN),auth());
        assertThat(quote.amount()).isEqualByComparingTo("4.50");
        assertThat(quote.currency()).isEqualTo("CHF");
    }

    private JwtAuthenticationToken auth(){
        Jwt jwt=Jwt.withTokenValue("test-token").header("alg","none").subject(userId.toString()).claim("roles",List.of("CUSTOMER"))
                .issuedAt(java.time.Instant.now()).expiresAt(java.time.Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt);
    }
}
