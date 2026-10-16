import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { currentBackofficeLocale } from './backoffice-locale';

const COPY = {
  en: { title: 'Marketplace security advisories', help: 'Core verifies signed Marketplace snapshots and keeps exact image digest bans permanently.', unavailable: 'No verified advisory snapshot has been received yet.', feed: 'Verified feed', stale: 'Stale', current: 'Current', releases: 'Release status', bans: 'Permanent image bans', targets: 'Local containment', plugin: 'Plugin', version: 'Version', status: 'Status', reason: 'Reason', digest: 'Image digest', target: 'Runtime target', outcome: 'Stop result', stopped: 'Stopped', failed: 'Stop failed', noReleases: 'No release advisories received.', noBans: 'No banned image digests.', noTargets: 'No plugin targets require containment.' },
  de: { title: 'Marketplace-Sicherheitshinweise', help: 'Core prüft signierte Marketplace-Snapshots und speichert exakte Image-Digest-Sperren dauerhaft.', unavailable: 'Es wurde noch kein verifizierter Sicherheitssnapshot empfangen.', feed: 'Verifizierter Feed', stale: 'Veraltet', current: 'Aktuell', releases: 'Release-Status', bans: 'Dauerhafte Image-Sperren', targets: 'Lokale Eindämmung', plugin: 'Plugin', version: 'Version', status: 'Status', reason: 'Grund', digest: 'Image-Digest', target: 'Laufzeit-Ziel', outcome: 'Stopp-Ergebnis', stopped: 'Gestoppt', failed: 'Stopp fehlgeschlagen', noReleases: 'Keine Release-Hinweise empfangen.', noBans: 'Keine gesperrten Image-Digests.', noTargets: 'Keine Plugin-Ziele benötigen Eindämmung.' },
  it: { title: 'Avvisi di sicurezza Marketplace', help: 'Core verifica snapshot Marketplace firmati e mantiene permanentemente i blocchi esatti dei digest immagine.', unavailable: 'Non è ancora stato ricevuto uno snapshot verificato.', feed: 'Feed verificato', stale: 'Obsoleto', current: 'Aggiornato', releases: 'Stato release', bans: 'Blocchi immagine permanenti', targets: 'Contenimento locale', plugin: 'Plugin', version: 'Versione', status: 'Stato', reason: 'Motivo', digest: 'Digest immagine', target: 'Runtime', outcome: 'Esito arresto', stopped: 'Arrestato', failed: 'Arresto non riuscito', noReleases: 'Nessun avviso release ricevuto.', noBans: 'Nessun digest immagine bloccato.', noTargets: 'Nessun target richiede contenimento.' },
  fr: { title: 'Avis de sécurité Marketplace', help: 'Core vérifie les instantanés Marketplace signés et conserve définitivement les interdictions de digest exactes.', unavailable: 'Aucun instantané vérifié n’a encore été reçu.', feed: 'Flux vérifié', stale: 'Obsolète', current: 'À jour', releases: 'État des versions', bans: 'Interdictions permanentes d’image', targets: 'Confinement local', plugin: 'Plugin', version: 'Version', status: 'État', reason: 'Motif', digest: 'Digest d’image', target: 'Cible runtime', outcome: 'Résultat de l’arrêt', stopped: 'Arrêté', failed: 'Échec de l’arrêt', noReleases: 'Aucun avis de version reçu.', noBans: 'Aucun digest d’image interdit.', noTargets: 'Aucune cible ne nécessite de confinement.' }
} as const;

@Component({
  selector: 'artio-extension-security',
  imports: [CommonModule],
  template: `
    <section class="panel table-panel" aria-labelledby="extension-security-title">
      <p class="eyebrow">{{ t('title') }}</p><h2 id="extension-security-title">{{ t('title') }}</h2><p class="muted">{{ t('help') }}</p>
      @if (!status?.sync || status.sync.sequence === 0) { <p class="muted" role="status">{{ t('unavailable') }}</p> }
      @else { <p class="muted" role="status">{{ t('feed') }} #{{ status.sync.sequence }} · {{ status.sync.stale ? t('stale') : t('current') }} · {{ status.sync.lastSuccessAt | date:'medium' }}</p> }
      <h3>{{ t('releases') }}</h3>
      @if (status?.releases?.length) { <table><thead><tr><th>{{ t('plugin') }}</th><th>{{ t('version') }}</th><th>{{ t('status') }}</th><th>{{ t('reason') }}</th></tr></thead><tbody>
        @for (release of status.releases; track release.pluginId + ':' + release.version) { <tr><td>{{ release.pluginId }}</td><td>{{ release.version }}</td><td>{{ release.securityStatus }} · {{ release.supportStatus }}</td><td>{{ release.reasonCode }}</td></tr> }
      </tbody></table> } @else { <p class="muted">{{ t('noReleases') }}</p> }
      <h3>{{ t('bans') }}</h3>
      @if (status?.bannedArtifacts?.length) { <table><thead><tr><th>{{ t('digest') }}</th><th>{{ t('plugin') }}</th><th>{{ t('version') }}</th><th>{{ t('reason') }}</th></tr></thead><tbody>
        @for (ban of status.bannedArtifacts; track ban.imageDigest) { <tr><td><code>{{ ban.imageDigest }}</code></td><td>{{ ban.pluginId }}</td><td>{{ ban.version }}</td><td>{{ ban.reasonCode }}</td></tr> }
      </tbody></table> } @else { <p class="muted">{{ t('noBans') }}</p> }
      <h3>{{ t('targets') }}</h3>
      @if (status?.blockedTargets?.length) { <table><thead><tr><th>{{ t('plugin') }}</th><th>{{ t('target') }}</th><th>{{ t('outcome') }}</th><th>{{ t('reason') }}</th></tr></thead><tbody>
        @for (target of status.blockedTargets; track target.runtimeIdentity) { <tr><td>{{ target.pluginId }}</td><td>{{ target.runtimeIdentity }}</td><td>{{ target.containmentStatus === 'STOPPED' ? t('stopped') : t('failed') }}</td><td>{{ target.lastErrorCode || '—' }}</td></tr> }
      </tbody></table> } @else { <p class="muted">{{ t('noTargets') }}</p> }
    </section>
  `
})
export class ExtensionSecurityComponent {
  @Input() status: any = null;
  t(key: keyof typeof COPY.en): string {
    const locale = currentBackofficeLocale() as keyof typeof COPY;
    return COPY[locale][key] ?? COPY.en[key];
  }
}
