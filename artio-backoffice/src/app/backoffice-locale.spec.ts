import { afterEach, describe, expect, it } from 'vitest';
import { backofficeText, setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';

describe('shared Backoffice loading copy', () => {
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Loading product media…', 'Loading catalog data…', 'Loading inventory…', 'Loading gift cards…'],
    ['de', 'Produktmedien werden geladen…', 'Katalogdaten werden geladen…', 'Bestand wird geladen…', 'Geschenkkarten werden geladen…'],
    ['it', 'Caricamento immagini prodotto…', 'Caricamento dati catalogo…', 'Caricamento inventario…', 'Caricamento gift card…'],
    ['fr', 'Chargement des médias produit…', 'Chargement des données du catalogue…', 'Chargement du stock…', 'Chargement des cartes cadeaux…'],
  ] as const)('provides consistent lazy-screen status labels in %s', (locale, media, catalog, inventory, giftCards) => {
    setBackofficeLocale(locale as BackofficeLocale);
    expect(backofficeText(locale as BackofficeLocale, 'loadingProductMedia')).toBe(media);
    expect(backofficeText(locale as BackofficeLocale, 'loadingCatalogData')).toBe(catalog);
    expect(backofficeText(locale as BackofficeLocale, 'loadingInventory')).toBe(inventory);
    expect(backofficeText(locale as BackofficeLocale, 'loadingGiftCards')).toBe(giftCards);
  });
});
