import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { currentBackofficeLocale, type BackofficeLocale } from './backoffice-locale';

type RegistryCredential = { registryHost: string; username: string; createdAt: string; updatedAt: string };

const COPY: Record<BackofficeLocale, Record<string, string>> = {
  en: { title: 'Private registry credentials', help: 'Credentials are encrypted by Core and can be used only for registry hosts explicitly trusted by Core configuration. Recent MFA is required.', host: 'Trusted registry host and optional port', username: 'Username', password: 'Password', save: 'Save credentials', remove: 'Remove', removeTitle:'Remove registry credentials?', removeHelp:'Credentials for {host} will be permanently removed.', confirmRemove:'Remove credentials', cancel:'Cancel', close:'Close', empty: 'No private registry credentials are configured.', loading: 'Loading registry credentials…', saved: 'Registry credentials saved.', removed: 'Registry credentials removed.', mfa: 'Sign in again with your authenticator code to manage private registry credentials.', error: 'Registry credentials could not be loaded.' },
  de: { title: 'Zugangsdaten für private Registries', help: 'Core verschlüsselt die Zugangsdaten und verwendet sie nur für explizit freigegebene Registry-Hosts. Eine aktuelle MFA-Prüfung ist erforderlich.', host: 'Freigegebener Registry-Host, optional mit Port', username: 'Benutzername', password: 'Passwort', save: 'Zugangsdaten speichern', remove: 'Entfernen', removeTitle:'Registry-Zugangsdaten entfernen?', removeHelp:'Die Zugangsdaten für {host} werden dauerhaft entfernt.', confirmRemove:'Zugangsdaten entfernen', cancel:'Abbrechen', close:'Schließen', empty: 'Keine Zugangsdaten für private Registries konfiguriert.', loading: 'Zugangsdaten werden geladen…', saved: 'Zugangsdaten gespeichert.', removed: 'Zugangsdaten entfernt.', mfa: 'Melden Sie sich mit Ihrem Authenticator-Code erneut an, um Zugangsdaten zu verwalten.', error: 'Zugangsdaten konnten nicht geladen werden.' },
  it: { title: 'Credenziali dei registry privati', help: 'Core cifra le credenziali e le usa solo per gli host autorizzati esplicitamente nella configurazione. È richiesta una MFA recente.', host: 'Host registry autorizzato e porta opzionale', username: 'Nome utente', password: 'Password', save: 'Salva credenziali', remove: 'Rimuovi', removeTitle:'Rimuovere le credenziali del registry?', removeHelp:'Le credenziali per {host} verranno rimosse definitivamente.', confirmRemove:'Rimuovi credenziali', cancel:'Annulla', close:'Chiudi', empty: 'Nessuna credenziale per registry privati configurata.', loading: 'Caricamento credenziali…', saved: 'Credenziali salvate.', removed: 'Credenziali rimosse.', mfa: 'Accedi di nuovo con il codice dell’autenticatore per gestire le credenziali.', error: 'Impossibile caricare le credenziali.' },
  fr: { title: 'Identifiants des registres privés', help: 'Core chiffre les identifiants et les utilise uniquement pour les hôtes autorisés dans sa configuration. Une MFA récente est requise.', host: 'Hôte autorisé et port facultatif', username: 'Nom d’utilisateur', password: 'Mot de passe', save: 'Enregistrer les identifiants', remove: 'Supprimer', removeTitle:'Supprimer ces identifiants ?', removeHelp:'Les identifiants de {host} seront définitivement supprimés.', confirmRemove:'Supprimer les identifiants', cancel:'Annuler', close:'Fermer', empty: 'Aucun identifiant de registre privé configuré.', loading: 'Chargement des identifiants…', saved: 'Identifiants enregistrés.', removed: 'Identifiants supprimés.', mfa: 'Reconnectez-vous avec votre code d’authentification pour gérer ces identifiants.', error: 'Impossible de charger les identifiants.' },
};

@Component({
  selector: 'artio-extension-registry-credentials',
  imports: [CommonModule, FormsModule],
  template: `
    <section class="panel table-panel" aria-labelledby="registry-credentials-title">
      <p class="eyebrow">{{ t('title') }}</p>
      <h2 id="registry-credentials-title">{{ t('title') }}</h2>
      <p class="muted">{{ t('help') }}</p>
      @if (message) { <p class="notice" role="status">{{ message }}</p> }
      @if (error) { <p class="notice error" role="alert">{{ error }}</p> }
      <form class="create-form" (ngSubmit)="save()">
        <label>{{ t('host') }}<input [(ngModel)]="registryHost" name="registryHost" maxlength="255" required autocomplete="url"></label>
        <label>{{ t('username') }}<input [(ngModel)]="username" name="registryUsername" maxlength="256" required autocomplete="username"></label>
        <label>{{ t('password') }}<input [(ngModel)]="password" name="registryPassword" type="password" maxlength="2048" required autocomplete="new-password"></label>
        <button class="primary" [disabled]="busy">{{ t('save') }}</button>
      </form>
      @if (loading) { <p class="muted loading-indicator" role="status">{{ t('loading') }}</p> }
      @if (!loading && !registries.length) { <p class="empty" role="status">{{ t('empty') }}</p> }
      @if (registries.length) {
        <table><thead><tr><th scope="col">{{ t('host') }}</th><th scope="col">{{ t('username') }}</th><th scope="col">{{ t('remove') }}</th></tr></thead><tbody>
          @for (registry of registries; track registry.registryHost) {
            <tr><td>{{ registry.registryHost }}</td><td>{{ registry.username }}</td><td><button type="button" class="link-button danger" [disabled]="busy" (click)="remove(registry.registryHost)">{{ t('remove') }}</button></td></tr>
          }
        </tbody></table>
      }
    </section>
    @if (removalHost) { <dialog #removeDialog class="refund-dialog" aria-labelledby="registry-remove-title" (cancel)="cancelRemove($event)" (close)="removalDialogClosed()"><form class="refund-dialog-content" (ngSubmit)="confirmRemove()"><button type="button" class="dialog-close-button" (click)="removeDialogRef?.nativeElement.close()" [disabled]="busy" [attr.aria-label]="t('close')" [attr.title]="t('close')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button><p class="eyebrow">{{t('title')}}</p><h2 id="registry-remove-title">{{t('removeTitle')}}</h2><p class="muted">{{t('removeHelp').replace('{host}',removalHost)}}</p><div class="refund-dialog-actions"><button type="button" class="secondary" [disabled]="busy" (click)="removeDialogRef?.nativeElement.close()">{{t('cancel')}}</button><button type="submit" class="danger-button" [disabled]="busy">{{t('confirmRemove')}}</button></div></form></dialog> }
  `,
})
export class ExtensionRegistryCredentialsComponent implements OnInit {
  @ViewChild('removeDialog') removeDialogRef?:ElementRef<HTMLDialogElement>;
  registries: RegistryCredential[] = [];
  registryHost = '';
  username = '';
  password = '';
  loading = true;
  busy = false;
  message = '';
  error = '';
  removalHost='';

  constructor(private readonly http: HttpClient) {}

  ngOnInit(): void { this.load(); }

  t(key: string): string { return COPY[currentBackofficeLocale()][key] ?? COPY.en[key] ?? key; }

  save(): void {
    const host = this.registryHost.trim();
    this.busy = true;
    this.message = '';
    this.error = '';
    this.http.put<RegistryCredential>(`/api/v1/admin/extensions/registries/${encodeURIComponent(host)}`, {
      username: this.username,
      password: this.password,
    }).subscribe({
      next: () => {
        this.password = '';
        this.message = this.t('saved');
        this.busy = false;
        this.load();
      },
      error: response => this.fail(response),
    });
  }

  remove(host: string): void {
    this.removalHost=host;this.message='';this.error='';queueMicrotask(()=>this.removeDialogRef?.nativeElement.showModal());
  }

  confirmRemove(): void {
    const host=this.removalHost;if(!host||this.busy)return;
    this.busy = true;
    this.message = '';
    this.error = '';
    this.http.delete(`/api/v1/admin/extensions/registries/${encodeURIComponent(host)}`).subscribe({
      next: () => {
        this.message = this.t('removed');
        this.busy = false;
        this.removeDialogRef?.nativeElement.close();
        this.load();
      },
      error: response => this.fail(response),
    });
  }

  cancelRemove(event:Event):void{event.preventDefault();if(!this.busy)this.removeDialogRef?.nativeElement.close()}
  removalDialogClosed():void{if(!this.busy)this.removalHost=''}

  private load(): void {
    this.loading = true;
    this.http.get<RegistryCredential[]>('/api/v1/admin/extensions/registries').subscribe({
      next: registries => { this.registries = registries; this.loading = false; },
      error: response => {
        this.registries = [];
        this.loading = false;
        this.error = this.responseMessage(response);
      },
    });
  }

  private fail(response: any): void {
    this.busy = false;
    this.error = this.responseMessage(response);
  }

  private responseMessage(response: any): string {
    const message = response.error?.detail ?? response.error?.message;
    return typeof message === 'string' && message.includes('Recent multi-factor authentication')
      ? this.t('mfa')
      : message ?? this.t('error');
  }
}
