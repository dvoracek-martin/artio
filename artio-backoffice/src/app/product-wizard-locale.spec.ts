import { afterEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { productWizardText } from './product-wizard-locale';

describe('product wizard copy', () => {
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Discard draft'],
    ['de', 'Entwurf verwerfen'],
    ['it', 'Scarta bozza'],
    ['fr', 'Abandonner le brouillon'],
  ] as const)('localizes draft discard in %s', (locale, discardDraft) => {
    setBackofficeLocale(locale as BackofficeLocale);
    expect(productWizardText('discardDraft')).toBe(discardDraft);
  });
});
