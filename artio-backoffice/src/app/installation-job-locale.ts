import type { BackofficeLocale } from './backoffice-locale';

const COPY: Record<BackofficeLocale, Record<string, string>> = {
  en: {
    REQUESTED: 'Installation queued', POLICY_CHECK: 'Checking installation policy', VERIFYING_SOURCE: 'Verifying Marketplace release',
    DOWNLOADING_OR_IMPORTING: 'Downloading plugin images', VERIFYING_ARTIFACTS: 'Verifying plugin images',
    REGISTERING_RELEASE: 'Preparing plugin installation', INSTALLED: 'Plugin installed',
    CONFIGURATION_REQUIRED: 'Installed; configuration required', READY: 'Installed; ready to configure',
    FAILED: 'Installation failed', CANCELLED: 'Installation cancelled', SECURITY_BLOCKED: 'Blocked by a Marketplace security advisory',
    unavailable: 'Installation status is temporarily unavailable',
  },
  de: {
    REQUESTED: 'Installation eingereiht', POLICY_CHECK: 'Installationsrichtlinie wird geprüft', VERIFYING_SOURCE: 'Marketplace-Version wird geprüft',
    DOWNLOADING_OR_IMPORTING: 'Plugin-Images werden heruntergeladen', VERIFYING_ARTIFACTS: 'Plugin-Images werden geprüft',
    REGISTERING_RELEASE: 'Plugin-Installation wird vorbereitet', INSTALLED: 'Plugin installiert',
    CONFIGURATION_REQUIRED: 'Installiert; Konfiguration erforderlich', READY: 'Installiert; bereit zur Konfiguration',
    FAILED: 'Installation fehlgeschlagen', CANCELLED: 'Installation abgebrochen', SECURITY_BLOCKED: 'Durch einen Marketplace-Sicherheitshinweis blockiert',
    unavailable: 'Installationsstatus ist vorübergehend nicht verfügbar',
  },
  fr: {
    REQUESTED: 'Installation mise en attente', POLICY_CHECK: 'Vérification de la politique d’installation', VERIFYING_SOURCE: 'Vérification de la version Marketplace',
    DOWNLOADING_OR_IMPORTING: 'Téléchargement des images du plugin', VERIFYING_ARTIFACTS: 'Vérification des images du plugin',
    REGISTERING_RELEASE: 'Préparation de l’installation du plugin', INSTALLED: 'Plugin installé',
    CONFIGURATION_REQUIRED: 'Installé ; configuration requise', READY: 'Installé ; prêt à configurer',
    FAILED: 'Échec de l’installation', CANCELLED: 'Installation annulée', SECURITY_BLOCKED: 'Bloqué par un avis de sécurité Marketplace',
    unavailable: 'État de l’installation temporairement indisponible',
  },
  it: {
    REQUESTED: 'Installazione in coda', POLICY_CHECK: 'Verifica dei criteri di installazione', VERIFYING_SOURCE: 'Verifica della release Marketplace',
    DOWNLOADING_OR_IMPORTING: 'Download delle immagini del plugin', VERIFYING_ARTIFACTS: 'Verifica delle immagini del plugin',
    REGISTERING_RELEASE: 'Preparazione dell’installazione del plugin', INSTALLED: 'Plugin installato',
    CONFIGURATION_REQUIRED: 'Installato; configurazione necessaria', READY: 'Installato; pronto per la configurazione',
    FAILED: 'Installazione non riuscita', CANCELLED: 'Installazione annullata', SECURITY_BLOCKED: 'Bloccato da un avviso di sicurezza Marketplace',
    unavailable: 'Stato dell’installazione temporaneamente non disponibile',
  },
};

const TERMINAL = new Set(['INSTALLED', 'CONFIGURATION_REQUIRED', 'READY', 'FAILED', 'CANCELLED', 'SECURITY_BLOCKED']);

export function isTerminalInstallationState(state: string): boolean {
  return TERMINAL.has(state);
}

export function installationJobStatusText(locale: BackofficeLocale, pluginId: string, version: string, state: string, jobId: string, errorCode?: string | null): string {
  const copy = COPY[locale] ?? COPY.en;
  const label = copy[state] ?? copy['unavailable'];
  const detail = state === 'FAILED' && errorCode ? ` · ${errorCode}` : '';
  return `${pluginId}@${version} · ${label}${detail} · ${jobId}`;
}

export function installationStatusUnavailableText(locale: BackofficeLocale, pluginId: string, version: string, jobId: string): string {
  return `${pluginId}@${version} · ${(COPY[locale] ?? COPY.en)['unavailable']} · ${jobId}`;
}
