import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BackofficeLocale, BackofficeTextKey, backofficeText, currentBackofficeLocale, setBackofficeLocale } from './backoffice-locale';

type LicenseStatus = {
  installationId: string; edition: string; state: string; features: string[];
  expiresAt: string | null; graceUntil: string | null; verificationStatus: string;
  refreshConfigured: boolean; refreshStatus: string; lastRefreshAttemptAt: string | null;
  lastRefreshSuccessAt: string | null; refreshInProgress: boolean;
};

@Component({
  selector: 'artio-license-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="panel">
      <div class="panel-heading"><div><p class="eyebrow">{{t('edition')}} &amp; {{t('availableFeatures')}}</p><h2>{{t('licenseTitle')}}</h2></div>
        <div class="license-controls"><label>{{t('language')}} <select [value]="locale" (change)="changeLocale($event)"><option value="de">Deutsch</option><option value="it">Italiano</option><option value="fr">Français</option><option value="en">English</option></select></label><button class="link-button" type="button" (click)="load()" [disabled]="loading">{{t('refreshStatusButton')}}</button></div>
      </div>
      <p class="muted">{{t('communityLicenseHelp')}}</p>
      <p *ngIf="loading" class="muted" role="status">{{t('loadingLicense')}}</p>
      <p *ngIf="error" class="error" role="alert">{{error}}</p>
      <ng-container *ngIf="status as license">
        <div class="metrics compact-metrics"><article><span>{{t('edition')}}</span><strong>{{license.edition}}</strong><small>{{license.state}}</small></article><article><span>{{t('verification')}}</span><strong>{{license.verificationStatus}}</strong><small>{{license.refreshConfigured?t('issuerConfigured'):t('localImportOnly')}}</small></article></div>
        <div class="table-panel"><h3>{{t('installation')}}</h3><p><code>{{license.installationId}}</code></p><p>{{t('availableFeatures')}}: <strong>{{featureNames(license.features)}}</strong></p>
          <p *ngIf="license.expiresAt">{{t('validUntil')}} {{formatDate(license.expiresAt)}}<span *ngIf="license.graceUntil"> · {{t('graceEnds')}} {{formatDate(license.graceUntil)}}</span></p><p *ngIf="!license.expiresAt" class="muted">{{t('noActiveEntitlement')}}</p>
        </div>
        <div class="table-panel"><h3>{{t('refreshStatus')}}</h3><p>{{t('refreshResult')}}: {{license.refreshStatus}}<span *ngIf="license.lastRefreshSuccessAt"> · {{t('lastSuccess')}} {{formatDate(license.lastRefreshSuccessAt)}}</span><span *ngIf="license.lastRefreshAttemptAt && !license.lastRefreshSuccessAt"> · {{t('lastAttempt')}} {{formatDate(license.lastRefreshAttemptAt)}}</span></p><button class="secondary" type="button" (click)="refresh()" [disabled]="!license.refreshConfigured || license.refreshInProgress || refreshing">{{refreshing||license.refreshInProgress?t('refreshing'):t('refreshIssuer')}}</button><small *ngIf="!license.refreshConfigured" class="muted">{{t('noIssuerConfigured')}}</small></div>
      </ng-container>
    </section>
    <section class="panel"><p class="eyebrow">{{t('ownerAction')}}</p><h2>{{t('installEntitlement')}}</h2><p class="muted">{{t('installExplanation')}}</p>
      <label>{{t('signedFile')}}<input #licenseFile type="file" accept="application/json,.json" (change)="readFile($event)" [disabled]="installing"></label>
      <p class="muted" *ngIf="selectedFile">{{selectedFile.name}} · {{selectedFile.size}} bytes</p>
      <p *ngIf="installError" class="error" role="alert">{{installError}}</p>
      <button class="primary" type="button" (click)="install(licenseFile)" [disabled]="!document || installing">{{installing?t('verifying'):t('verifyInstall')}}</button>
    </section>`
})
export class LicenseAdminComponent {
  private readonly http = inject(HttpClient);
  locale: BackofficeLocale = currentBackofficeLocale();
  status: LicenseStatus | null = null;
  error = '';
  installError = '';
  document = '';
  selectedFile: File | null = null;
  loading = false;
  refreshing = false;
  installing = false;

  constructor() { this.load(); }

  t(key: BackofficeTextKey): string { return backofficeText(this.locale, key); }
  changeLocale(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value === 'de' || value === 'it' || value === 'fr' || value === 'en') { this.locale = value; setBackofficeLocale(value); }
  }
  formatDate(value: string): string {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat(this.locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
  }
  featureNames(features: string[]): string {
    if (!features.length) return this.t('noEnterpriseFeatures');
    const names: Record<string, BackofficeTextKey> = { LOYALTY: 'featureLoyalty', SUBSCRIPTIONS: 'featureSubscriptions', ADVANCED_REPORTING: 'featureAdvancedReporting' };
    return features.map(feature => names[feature] ? this.t(names[feature]) : feature).join(', ');
  }

  load(): void {
    this.loading = true;
    this.http.get<LicenseStatus>('/api/v1/admin/system/license').subscribe({
      next: status => { this.status = status; this.loading = false; this.error = ''; },
      error: () => { this.loading = false; this.error = this.t('licenseLoadError'); }
    });
  }

  readFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.document = '';
    this.selectedFile = null;
    this.installError = '';
    if (!file) return;
    if (file.size > 65536) { this.installError = this.t('fileTooLarge'); input.value = ''; return; }
    this.selectedFile = file;
    const reader = new FileReader();
    reader.onload = () => {
      const value = reader.result;
      if (typeof value !== 'string') { this.installError = this.t('fileReadError'); return; }
      this.document = value;
    };
    reader.onerror = () => { this.installError = this.t('fileSelectedReadError'); this.selectedFile = null; };
    reader.readAsText(file, 'UTF-8');
  }

  install(fileInput: HTMLInputElement): void {
    if (!this.document || this.installing) return;
    this.installing = true;
    this.installError = '';
    this.http.post<LicenseStatus>('/api/v1/admin/system/license', this.document, { headers: { 'Content-Type': 'application/json' } }).subscribe({
      next: status => { this.status = status; this.document = ''; this.selectedFile = null; fileInput.value = ''; this.installing = false; this.installError = ''; },
      error: () => { this.installing = false; this.installError = this.t('rejectedLicense'); }
    });
  }

  refresh(): void {
    if (!this.status?.refreshConfigured || this.refreshing || this.status.refreshInProgress) return;
    this.refreshing = true;
    this.http.post<LicenseStatus>('/api/v1/admin/system/license/refresh', {}).subscribe({
      next: status => { this.status = status; this.refreshing = false; },
      error: () => { this.refreshing = false; this.error = this.t('refreshError'); }
    });
  }
}
