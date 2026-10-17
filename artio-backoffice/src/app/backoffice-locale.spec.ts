import { afterEach, describe, expect, it } from 'vitest';
import { backofficeText, setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';

describe('shared Backoffice loading copy', () => {
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Account settings', 'Dismiss'],
    ['de', 'Kontoeinstellungen', 'Schließen'],
    ['it', 'Impostazioni account', 'Chiudi'],
    ['fr', 'Paramètres du compte', 'Fermer'],
  ] as const)('provides localized accessible shell actions in %s', (locale, accountSettings, dismiss) => {
    const language = locale as BackofficeLocale;
    expect(backofficeText(language, 'shellAccountSettings')).toBe(accountSettings);
    expect(backofficeText(language, 'shellDismiss')).toBe(dismiss);
  });

  it.each([
    ['en', 'Email verified', 'Email not verified', 'Manual adjustment'],
    ['de', 'E-Mail bestätigt', 'E-Mail nicht bestätigt', 'Manuelle Anpassung'],
    ['it', 'Email verificata', 'Email non verificata', 'Rettifica manuale'],
    ['fr', 'E-mail vérifié', 'E-mail non vérifié', 'Ajustement manuel'],
  ] as const)('localizes customer verification and manual loyalty history in %s', (locale, verified, unverified, manual) => {
    const language = locale as BackofficeLocale;
    expect(backofficeText(language, 'usersEmailVerified')).toBe(verified);
    expect(backofficeText(language, 'usersEmailUnverified')).toBe(unverified);
    expect(backofficeText(language, 'usersManualTransaction')).toBe(manual);
  });

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

  it.each([
    ['en', 'Could not load this information.', 'Enter valid JSON.', 'Choose a product variant.', 'Market price country codes must be unique.'],
    ['de', 'Diese Daten konnten nicht geladen werden.', 'Geben Sie gültiges JSON ein.', 'Wählen Sie eine Produktvariante aus.', 'Die Ländercodes für Marktpreise müssen eindeutig sein.'],
    ['it', 'Impossibile caricare questi dati.', 'Inserisci JSON valido.', 'Scegli una variante del prodotto.', 'I codici paese dei prezzi di mercato devono essere univoci.'],
    ['fr', 'Impossible de charger ces informations.', 'Saisissez un JSON valide.', 'Choisissez une variante du produit.', 'Les codes pays des prix de marché doivent être uniques.']
  ] as const)('provides localized admin request and validation fallbacks in %s', (locale, loadFailure, invalidJson, selectionRequired, uniqueCountries) => {
    const language = locale as BackofficeLocale;
    expect(backofficeText(language, 'adminLoadFailure')).toBe(loadFailure);
    expect(backofficeText(language, 'adminInvalidJson')).toBe(invalidJson);
    expect(backofficeText(language, 'adminSelectionRequired')).toBe(selectionRequired);
    expect(backofficeText(language, 'adminUniqueCountries')).toBe(uniqueCountries);
  });
});
