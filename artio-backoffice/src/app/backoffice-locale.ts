export type BackofficeLocale = 'de' | 'it' | 'fr' | 'en';

export function defineBackofficeLocaleCopy<const T extends Record<BackofficeLocale, Record<string, string>>>(
  copy: T & { [locale in BackofficeLocale]: Record<keyof T['en'], string> },
): T {
  return copy;
}

import { BACKOFFICE_COPY_EN } from './backoffice-locale.en';

export type BackofficeTextKey = keyof typeof BACKOFFICE_COPY_EN;
type BackofficeCopy = Readonly<Record<BackofficeTextKey, string>>;
const translations: Partial<Record<BackofficeLocale, BackofficeCopy>> = { en: BACKOFFICE_COPY_EN };
const pendingLoads = new Map<BackofficeLocale, Promise<void>>();

export function loadBackofficeLocale(locale: BackofficeLocale): Promise<void> {
  if (translations[locale]) return Promise.resolve();
  const pending = pendingLoads.get(locale);
  if (pending) return pending;
  const load = (locale === 'de'
    ? import('./backoffice-locale.de').then(module => module.BACKOFFICE_COPY_DE)
    : locale === 'it'
      ? import('./backoffice-locale.it').then(module => module.BACKOFFICE_COPY_IT)
      : import('./backoffice-locale.fr').then(module => module.BACKOFFICE_COPY_FR))
    .then(copy => { translations[locale] = copy; })
    .finally(() => pendingLoads.delete(locale));
  pendingLoads.set(locale, load);
  return load;
}

export function currentBackofficeLocale(): BackofficeLocale {
  try {
    const saved = localStorage.getItem('artio-backoffice-language');
    if (saved === 'de' || saved === 'it' || saved === 'fr' || saved === 'en') return saved;
  } catch { /* Browser storage may be disabled; use the browser's language. */ }
  const languages = typeof navigator === 'undefined' ? [] : [...(navigator.languages ?? []), navigator.language];
  for (const language of languages) {
    const match = language.toLowerCase().split('-')[0];
    if (match === 'de' || match === 'it' || match === 'fr' || match === 'en') return match;
  }
  return 'en';
}

export function setBackofficeLocale(locale: BackofficeLocale): void {
  try { localStorage.setItem('artio-backoffice-language', locale); }
  catch { /* A selected language still applies for this component session. */ }
  void loadBackofficeLocale(locale).catch(() => undefined);
}

export function backofficeText(locale: BackofficeLocale, key: BackofficeTextKey): string {
  return translations[locale]?.[key] ?? BACKOFFICE_COPY_EN[key];
}
