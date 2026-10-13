package io.artio.api.notifications;

import java.util.Locale;

/** Small plain-text transactional templates for the storefront's supported locales. */
public final class OrderConfirmationTemplates {
    private OrderConfirmationTemplates() { }

    public static Content confirmation(String requestedLocale, String orderNumber) {
        String language = requestedLocale == null ? "en" : requestedLocale.split("[-_]")[0].toLowerCase(Locale.ROOT);
        return switch (language) {
            case "de" -> new Content("Bestellung eingegangen", "Ihre Bestellung " + orderNumber
                    + " ist eingegangen. Vielen Dank für Ihren Einkauf bei Artio.");
            case "fr" -> new Content("Commande reçue", "Votre commande " + orderNumber
                    + " a bien été reçue. Merci d’avoir choisi Artio.");
            case "it" -> new Content("Ordine ricevuto", "Abbiamo ricevuto il suo ordine " + orderNumber
                    + ". Grazie per aver scelto Artio.");
            default -> new Content("Artio order received", "Your order " + orderNumber
                    + " has been received. Thank you for shopping with Artio.");
        };
    }

    public record Content(String subject, String body) { }
}
