import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

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
      <div class="panel-heading"><div><p class="eyebrow">EDITION &amp; ENTITLEMENTS</p><h2>Artio license</h2></div><button class="link-button" type="button" (click)="load()" [disabled]="loading">Refresh status</button></div>
      <p class="muted">Community keeps commerce, plugin management and existing customer obligations available. Loyalty, subscriptions and advanced reports need their matching Enterprise feature.</p>
      <p *ngIf="loading" class="muted" role="status">Loading license status…</p>
      <p *ngIf="error" class="error" role="alert">{{error}}</p>
      <ng-container *ngIf="status as license">
        <div class="metrics"><article><span>Edition</span><strong>{{license.edition}}</strong><small>{{license.state}}</small></article><article><span>Verification</span><strong>{{license.verificationStatus}}</strong><small>{{license.refreshConfigured?'Issuer refresh configured':'Local import only'}}</small></article></div>
        <div class="table-panel"><h3>Installation</h3><p><code>{{license.installationId}}</code></p><p>Available features: <strong>{{license.features.length?license.features.join(', '):'None · Community capabilities remain available'}}</strong></p><p *ngIf="license.expiresAt">Valid until {{license.expiresAt | date:'medium'}}<span *ngIf="license.graceUntil"> · Grace ends {{license.graceUntil | date:'medium'}}</span></p><p *ngIf="!license.expiresAt" class="muted">No active Enterprise entitlement is installed.</p></div>
        <div class="table-panel"><h3>Controlled refresh</h3><p>Refresh result: {{license.refreshStatus}}<span *ngIf="license.lastRefreshSuccessAt"> · Last success {{license.lastRefreshSuccessAt | date:'medium'}}</span><span *ngIf="license.lastRefreshAttemptAt && !license.lastRefreshSuccessAt"> · Last attempt {{license.lastRefreshAttemptAt | date:'medium'}}</span></p><button class="secondary" type="button" (click)="refresh()" [disabled]="!license.refreshConfigured || license.refreshInProgress || refreshing">{{refreshing||license.refreshInProgress?'Refreshing…':'Refresh from issuer'}}</button><small *ngIf="!license.refreshConfigured" class="muted">No issuer URL is configured. Import a signed license document below.</small></div>
      </ng-container>
    </section>
    <section class="panel"><p class="eyebrow">OWNER ACTION</p><h2>Install signed entitlement</h2><p class="muted">Select the signed JSON envelope provided for this installation. Artio verifies it against the configured public trust keys. The file and its contents are not stored in the browser after installation.</p>
      <label>Signed entitlement JSON<input #licenseFile type="file" accept="application/json,.json" (change)="readFile($event)" [disabled]="installing"></label>
      <p class="muted" *ngIf="selectedFile">{{selectedFile.name}} · {{selectedFile.size}} bytes</p>
      <p *ngIf="installError" class="error" role="alert">{{installError}}</p>
      <button class="primary" type="button" (click)="install(licenseFile)" [disabled]="!document || installing">{{installing?'Verifying…':'Verify and install'}}</button>
    </section>`
})
export class LicenseAdminComponent {
  private readonly http = inject(HttpClient);
  status: LicenseStatus | null = null;
  error = '';
  installError = '';
  document = '';
  selectedFile: File | null = null;
  loading = false;
  refreshing = false;
  installing = false;

  constructor() { this.load(); }

  load(): void {
    this.loading = true;
    this.http.get<LicenseStatus>('/api/v1/admin/system/license').subscribe({
      next: status => { this.status = status; this.loading = false; this.error = ''; },
      error: response => { this.loading = false; this.error = response.error?.message ?? 'Could not read license status.'; }
    });
  }

  readFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.document = '';
    this.selectedFile = null;
    this.installError = '';
    if (!file) return;
    if (file.size > 65536) { this.installError = 'The signed license file exceeds 64 KiB.'; input.value = ''; return; }
    this.selectedFile = file;
    const reader = new FileReader();
    reader.onload = () => {
      const value = reader.result;
      if (typeof value !== 'string') { this.installError = 'Could not read the signed license file as text.'; return; }
      this.document = value;
    };
    reader.onerror = () => { this.installError = 'Could not read the selected license file.'; this.selectedFile = null; };
    reader.readAsText(file, 'UTF-8');
  }

  install(fileInput: HTMLInputElement): void {
    if (!this.document || this.installing) return;
    this.installing = true;
    this.installError = '';
    this.http.post<LicenseStatus>('/api/v1/admin/system/license', this.document, { headers: { 'Content-Type': 'application/json' } }).subscribe({
      next: status => { this.status = status; this.document = ''; this.selectedFile = null; fileInput.value = ''; this.installing = false; this.installError = ''; },
      error: response => { this.installing = false; this.installError = response.error?.message ?? 'The signed license was rejected. Existing license status is unchanged.'; }
    });
  }

  refresh(): void {
    if (!this.status?.refreshConfigured || this.refreshing || this.status.refreshInProgress) return;
    this.refreshing = true;
    this.http.post<LicenseStatus>('/api/v1/admin/system/license/refresh', {}).subscribe({
      next: status => { this.status = status; this.refreshing = false; },
      error: response => { this.refreshing = false; this.error = response.error?.message ?? 'Could not request license refresh.'; }
    });
  }
}
