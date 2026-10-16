import { CommonModule } from '@angular/common';
import { HttpClient, HttpEventType, HttpHeaders } from '@angular/common/http';
import { Component, Input, OnDestroy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { currentBackofficeLocale } from './backoffice-locale';

@Component({
  selector: 'artio-extension-local-archive',
  imports: [CommonModule, FormsModule],
  template: `
    <section class="local-archive panel" aria-labelledby="local-archive-title">
      <p class="eyebrow">{{ text().eyebrow }}</p>
      <h2 id="local-archive-title">{{ text().title }}</h2>
      <p class="muted">{{ text().help }}</p>
      <form class="create-form" (ngSubmit)="upload()">
        <label>{{ text().plugin }}
          <select [(ngModel)]="pluginId" name="pluginId" required>
            <option value="">{{ text().choose }}</option>
            @for (extension of installableExtensions; track extension.id) {
              <option [value]="extension.id">{{ extension.name }} ({{ extension.id }})</option>
            }
          </select>
        </label>
        <label>{{ text().archive }}
          <input type="file" accept=".tar,application/x-tar,application/vnd.oci.image.layout.v1.tar" required
            (change)="selectFile($event)">
        </label>
        <button class="primary" type="submit" [disabled]="!pluginId || !file || uploading">{{ uploading ? text().uploading : text().submit }}</button>
      </form>
      @if (uploading) { <progress [value]="progress" max="100" aria-label="{{ text().progress }}">{{ progress }}%</progress> }
      @if (message) { <p class="muted" role="status" aria-live="polite">{{ message }}</p> }
      @if (error) { <p class="notice error" role="alert">{{ error }}</p> }
    </section>
  `,
  styles: [`.local-archive{margin:1rem 0}.local-archive h2{margin:.2rem 0}.local-archive progress{width:100%;margin-top:.75rem}`],
})
export class ExtensionLocalArchiveComponent implements OnDestroy {
  @Input() extensions: any[] = [];

  pluginId = '';
  file: File | null = null;
  uploading = false;
  progress = 0;
  message = '';
  error = '';
  private pollTimer?: ReturnType<typeof setTimeout>;

  constructor(private readonly http: HttpClient) {}

  ngOnDestroy(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
  }

  get installableExtensions(): any[] {
    return this.extensions.filter((extension) => ['CONFIGURED', 'INSTALLED', 'DISABLED', 'FAILED'].includes(extension.lifecycleState));
  }

  text() {
    const locale = currentBackofficeLocale();
    type ArchiveCopy = { eyebrow: string; title: string; help: string; plugin: string; choose: string; archive: string;
      submit: string; uploading: string; progress: string; queued: string; failed: string };
    const copy: Record<string, ArchiveCopy> = {
      en: { eyebrow: 'LOCAL PLUGIN SOURCE', title: 'Install from an archive', help: 'Upload a Docker-save or OCI image archive up to 2 GiB. Artio verifies its digests and contents before Docker imports it.', plugin: 'Registered plugin', choose: 'Choose a plugin', archive: 'Plugin image archive', submit: 'Upload and install', uploading: 'Uploading archive…', progress: 'Upload progress', queued: 'Archive accepted. Installation job: ', failed: 'Archive upload failed. Check recent MFA and try again.' },
      de: { eyebrow: 'LOKALE PLUGIN-QUELLE', title: 'Aus einem Archiv installieren', help: 'Laden Sie ein Docker-save- oder OCI-Image-Archiv mit bis zu 2 GiB hoch. Artio prüft Digests und Inhalt, bevor Docker es importiert.', plugin: 'Registriertes Plugin', choose: 'Plugin auswählen', archive: 'Plugin-Image-Archiv', submit: 'Hochladen und installieren', uploading: 'Archiv wird hochgeladen…', progress: 'Upload-Fortschritt', queued: 'Archiv angenommen. Installationsauftrag: ', failed: 'Upload fehlgeschlagen. Prüfen Sie die aktuelle MFA und versuchen Sie es erneut.' },
      fr: { eyebrow: 'SOURCE LOCALE DU PLUGIN', title: 'Installer depuis une archive', help: 'Téléversez une archive d’image Docker-save ou OCI de 2 Gio maximum. Artio vérifie ses digests et son contenu avant l’import Docker.', plugin: 'Plugin enregistré', choose: 'Choisir un plugin', archive: 'Archive d’image du plugin', submit: 'Téléverser et installer', uploading: 'Téléversement…', progress: 'Progression du téléversement', queued: 'Archive acceptée. Tâche d’installation : ', failed: 'Échec du téléversement. Vérifiez la MFA récente et réessayez.' },
    };
    return copy[locale] ?? copy['en'];
  }

  selectFile(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
    this.error = '';
    this.message = '';
  }

  upload(): void {
    if (!this.file || !this.pluginId || this.uploading) return;
    const form = new FormData();
    form.append('archive', this.file, this.file.name);
    this.uploading = true;
    this.progress = 0;
    this.message = '';
    this.error = '';
    const headers = new HttpHeaders({ 'Idempotency-Key': crypto.randomUUID() });
    this.http.post<any>(`/api/v1/admin/extensions/${encodeURIComponent(this.pluginId)}/installations/archive`, form, {
      headers,
      observe: 'events',
      reportProgress: true,
    }).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) this.progress = Math.round(event.loaded * 100 / event.total);
        if (event.type === HttpEventType.Response) {
          this.uploading = false;
          const jobId = event.body?.jobId;
          this.message = `${this.text().queued}${jobId ?? '—'}`;
          if (jobId) this.pollInstallation(jobId);
        }
      },
      error: (failure) => {
        this.error = failure?.error?.message ?? this.text().failed;
        this.uploading = false;
      },
    });
  }

  private pollInstallation(jobId: string): void {
    this.http.get<any>(`/api/v1/admin/extensions/installations/${encodeURIComponent(jobId)}`).subscribe({
      next: (job) => {
        this.message = `${this.text().queued}${jobId} · ${job.state}`;
        if (!['INSTALLED', 'FAILED', 'CANCELLED', 'SECURITY_BLOCKED', 'CONFIGURATION_REQUIRED', 'READY'].includes(job.state)) {
          this.pollTimer = setTimeout(() => this.pollInstallation(jobId), 2000);
        }
      },
      error: () => {
        this.error = this.text().failed;
      },
    });
  }
}
