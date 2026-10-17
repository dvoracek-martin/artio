import { describe, expect, it } from 'vitest';
import { backofficeText, loadBackofficeLocale, type BackofficeLocale } from './backoffice-locale';

describe('regional system settings localization', () => {
  it.each([
    ['en', 'Installation configuration', 'Recommended integrations', 'Waiting for release', 'Choose at least one content language.'],
    ['de', 'Installationskonfiguration', 'Empfohlene Integrationen', 'Wartet auf Veröffentlichung', 'Wählen Sie mindestens eine Inhaltssprache aus.'],
    ['it', 'Configurazione dell’installazione', 'Integrazioni consigliate', 'In attesa del rilascio', 'Scegli almeno una lingua dei contenuti.'],
    ['fr', 'Configuration de l’installation', 'Intégrations recommandées', 'En attente de publication', 'Choisissez au moins une langue de contenu.']
  ] as const)('provides localized regional setup and plugin states in %s', async (locale, installation, recommendations, waiting, requiredLanguage) => {
    const language = locale as BackofficeLocale;
    await loadBackofficeLocale(language);
    expect(backofficeText(language, 'systemInstallation')).toBe(installation);
    expect(backofficeText(language, 'systemRecommendations')).toBe(recommendations);
    expect(backofficeText(language, 'systemWaitingRelease')).toBe(waiting);
    expect(backofficeText(language, 'systemLanguageRequired')).toBe(requiredLanguage);
  });
});
