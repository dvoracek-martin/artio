package io.artio.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderConfirmationTemplatesTests {
    @Test
    void resolvesSupportedLanguageAndFallsBackToEnglish() {
        assertThat(OrderConfirmationTemplates.confirmation("de-CH", "order-1").subject()).isEqualTo("Bestellung eingegangen");
        assertThat(OrderConfirmationTemplates.confirmation("fr", "order-1").body()).contains("order-1", "Merci");
        assertThat(OrderConfirmationTemplates.confirmation("es-MX", "order-1").subject()).isEqualTo("Artio order received");
        assertThat(OrderConfirmationTemplates.confirmation(null, "order-1").body()).contains("order-1", "Thank you");
    }
}
