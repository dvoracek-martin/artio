import { afterEach, describe, expect, it } from 'vitest';
import { LocaleService, type StoreLocale } from './locale.service';

describe('LocaleService', () => {
  const locale = new LocaleService();
  afterEach(() => localStorage.removeItem('artio-locale'));

  it.each([
    ['en', 'Search products'],
    ['de', 'Produkte suchen'],
    ['fr', 'Rechercher des produits'],
    ['it', 'Cerca prodotti']
  ] as const)('returns storefront search copy in %s', (language, expected) => {
    expect(locale.translate('search', language as StoreLocale)).toBe(expected);
  });

  it.each([
    ['en', 'Prices and availability are confirmed at checkout.'],
    ['de', 'Preise und Verfügbarkeit werden an der Kasse bestätigt.'],
    ['fr', 'Les prix et la disponibilité sont confirmés lors du paiement.'],
    ['it', 'Prezzi e disponibilità sono confermati al checkout.']
  ] as const)('returns the product price disclaimer in %s', (language, expected) => {
    expect(locale.translate('productPriceDisclaimer', language as StoreLocale)).toBe(expected);
  });

  it.each([
    ['en', 'Cancel order', 'Cancel this order and expire its pending payment checkout?', 'Confirm cancellation', 'Keep order'],
    ['de', 'Bestellung stornieren', 'Bestellung stornieren und den ausstehenden Zahlungsvorgang beenden?', 'Stornierung bestätigen', 'Bestellung behalten'],
    ['fr', 'Annuler la commande', 'Annuler cette commande et expirer le paiement en attente ?', 'Confirmer l’annulation', 'Conserver la commande'],
    ['it', 'Annulla ordine', 'Annullare questo ordine e la sessione di pagamento in sospeso?', 'Conferma annullamento', 'Mantieni ordine']
  ] as const)('localizes customer order cancellation copy in %s', (language, title, message, confirm, keep) => {
    const supportedLocale = language as StoreLocale;
    expect(locale.translate('cancelOrder', supportedLocale)).toBe(title);
    expect(locale.translate('cancelConfirm', supportedLocale)).toBe(message);
    expect(locale.translate('confirmCancelOrder', supportedLocale)).toBe(confirm);
    expect(locale.translate('keepOrder', supportedLocale)).toBe(keep);
  });

  it.each([
    ['de-CH', 'de'],
    ['fr_CH', 'fr'],
    ['it', 'it'],
    ['xx-YY', 'en'],
    [undefined, 'en']
  ] as const)('normalizes %s to a supported storefront locale', (input, expected) => {
    expect(locale.normalize(input)).toBe(expected);
  });

  it('uses the saved locale and falls back to English for unknown copy keys', () => {
    locale.set('fr');
    expect(locale.current()).toBe('fr');
    expect(locale.translate('search')).toBe('Rechercher des produits');
    expect(locale.translate('missingCopyKey')).toBe('missingCopyKey');
  });
});
