import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, EventEmitter, Input, OnChanges, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LocaleService, StoreLocale } from './locale.service';

const ACCOUNT_MESSAGES: Record<StoreLocale, Record<string, string>> = {
  en: {forgotPassword:'Forgot your password?',resetIntro:'Enter the email address on your account and we’ll send recovery instructions.',sendReset:'Send recovery link',resetPassword:'Choose a new password',newPassword:'New password',savePassword:'Save password',resetLinkMissing:'This recovery link is missing or invalid.',resetComplete:'Your password is updated. Taking you to sign in…',backToSignIn:'Back to sign in',accountSettings:'Account settings',personalDetails:'Personal details',currentPassword:'Current password',saveChanges:'Save changes',changeEmail:'Change email address',newEmail:'New email address',requestEmailChange:'Request email change',emailConfirmationToken:'Email confirmation code',confirmEmailChange:'Confirm email change',changePassword:'Change password',profileSaved:'Your details have been updated.',passwordChanged:'Password updated. Please sign in again.',devResetLink:'Open the local recovery link',accountUnavailable:'Could not load your account.',profileSaveUnavailable:'Could not update your details.',passwordChangeUnavailable:'Could not update your password.',emailChangeUnavailable:'Could not change your email address.',resetRequestUnavailable:'Could not request password recovery.',resetCompleteUnavailable:'Could not reset your password.'},
  de: {forgotPassword:'Passwort vergessen?',resetIntro:'Geben Sie die E-Mail-Adresse Ihres Kontos ein. Wir senden Ihnen Anweisungen zur Wiederherstellung.',sendReset:'Link senden',resetPassword:'Neues Passwort wählen',newPassword:'Neues Passwort',savePassword:'Passwort speichern',resetLinkMissing:'Dieser Wiederherstellungslink fehlt oder ist ungültig.',resetComplete:'Passwort aktualisiert. Weiter zur Anmeldung …',backToSignIn:'Zurück zur Anmeldung',accountSettings:'Kontoeinstellungen',personalDetails:'Persönliche Daten',currentPassword:'Aktuelles Passwort',saveChanges:'Änderungen speichern',changeEmail:'E-Mail-Adresse ändern',newEmail:'Neue E-Mail-Adresse',requestEmailChange:'E-Mail-Änderung anfragen',emailConfirmationToken:'E-Mail-Bestätigungscode',confirmEmailChange:'E-Mail-Änderung bestätigen',changePassword:'Passwort ändern',profileSaved:'Ihre Angaben wurden aktualisiert.',passwordChanged:'Passwort aktualisiert. Bitte melden Sie sich erneut an.',devResetLink:'Lokalen Wiederherstellungslink öffnen',accountUnavailable:'Konto konnte nicht geladen werden.',profileSaveUnavailable:'Angaben konnten nicht aktualisiert werden.',passwordChangeUnavailable:'Passwort konnte nicht aktualisiert werden.',emailChangeUnavailable:'E-Mail-Adresse konnte nicht geändert werden.',resetRequestUnavailable:'Passwortwiederherstellung konnte nicht angefordert werden.',resetCompleteUnavailable:'Passwort konnte nicht zurückgesetzt werden.'},
  fr: {forgotPassword:'Mot de passe oublié ?',resetIntro:'Saisissez l’adresse e-mail de votre compte pour recevoir les instructions de récupération.',sendReset:'Envoyer le lien',resetPassword:'Choisir un nouveau mot de passe',newPassword:'Nouveau mot de passe',savePassword:'Enregistrer le mot de passe',resetLinkMissing:'Ce lien de récupération est manquant ou invalide.',resetComplete:'Mot de passe mis à jour. Redirection vers la connexion…',backToSignIn:'Retour à la connexion',accountSettings:'Paramètres du compte',personalDetails:'Informations personnelles',currentPassword:'Mot de passe actuel',saveChanges:'Enregistrer les modifications',changeEmail:'Modifier l’adresse e-mail',newEmail:'Nouvelle adresse e-mail',requestEmailChange:'Demander le changement',emailConfirmationToken:'Code de confirmation de l’e-mail',confirmEmailChange:'Confirmer le changement d’e-mail',changePassword:'Modifier le mot de passe',profileSaved:'Vos informations ont été mises à jour.',passwordChanged:'Mot de passe mis à jour. Veuillez vous reconnecter.',devResetLink:'Ouvrir le lien local de récupération',accountUnavailable:'Impossible de charger votre compte.',profileSaveUnavailable:'Impossible de mettre à jour vos informations.',passwordChangeUnavailable:'Impossible de mettre à jour le mot de passe.',emailChangeUnavailable:'Impossible de modifier l’adresse e-mail.',resetRequestUnavailable:'Impossible de demander la récupération du mot de passe.',resetCompleteUnavailable:'Impossible de réinitialiser le mot de passe.'},
  it: {forgotPassword:'Password dimenticata?',resetIntro:'Inserisci l’indirizzo e-mail del tuo account per ricevere le istruzioni di recupero.',sendReset:'Invia il link',resetPassword:'Scegli una nuova password',newPassword:'Nuova password',savePassword:'Salva password',resetLinkMissing:'Il link di recupero è mancante o non valido.',resetComplete:'Password aggiornata. Reindirizzamento all’accesso…',backToSignIn:'Torna all’accesso',accountSettings:'Impostazioni account',personalDetails:'Dati personali',currentPassword:'Password attuale',saveChanges:'Salva modifiche',changeEmail:'Modifica indirizzo e-mail',newEmail:'Nuovo indirizzo e-mail',requestEmailChange:'Richiedi modifica e-mail',emailConfirmationToken:'Codice di conferma e-mail',confirmEmailChange:'Conferma modifica e-mail',changePassword:'Modifica password',profileSaved:'I tuoi dati sono stati aggiornati.',passwordChanged:'Password aggiornata. Accedi di nuovo.',devResetLink:'Apri il link di recupero locale',accountUnavailable:'Impossibile caricare l’account.',profileSaveUnavailable:'Impossibile aggiornare i tuoi dati.',passwordChangeUnavailable:'Impossibile aggiornare la password.',emailChangeUnavailable:'Impossibile modificare l’indirizzo e-mail.',resetRequestUnavailable:'Impossibile richiedere il recupero della password.',resetCompleteUnavailable:'Impossibile reimpostare la password.'},
};

const LANGUAGE_MESSAGES: Record<StoreLocale, Record<string, string>> = {
  en: {preferredLanguage:'Preferred language',languageSaved:'Your preferred language has been saved.',languageUnavailable:'Could not save your preferred language.',language_en:'English',language_de:'German',language_fr:'French',language_it:'Italian'},
  de: {preferredLanguage:'Bevorzugte Sprache',languageSaved:'Ihre bevorzugte Sprache wurde gespeichert.',languageUnavailable:'Bevorzugte Sprache konnte nicht gespeichert werden.',language_en:'Englisch',language_de:'Deutsch',language_fr:'Französisch',language_it:'Italienisch'},
  fr: {preferredLanguage:'Langue préférée',languageSaved:'Votre langue préférée a été enregistrée.',languageUnavailable:'Impossible d’enregistrer votre langue préférée.',language_en:'Anglais',language_de:'Allemand',language_fr:'Français',language_it:'Italien'},
  it: {preferredLanguage:'Lingua preferita',languageSaved:'La lingua preferita è stata salvata.',languageUnavailable:'Impossibile salvare la lingua preferita.',language_en:'Inglese',language_de:'Tedesco',language_fr:'Francese',language_it:'Italiano'},
};

@Component({
  selector: 'artio-account',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './account.component.html',
})
export class AccountComponent implements OnChanges {
  @Input() page = 'account';
  @Input() locale: StoreLocale = 'en';
  @Input() availableLanguages: string[] = ['en', 'de', 'fr', 'it'];
  @Output() signedOut = new EventEmitter<void>();
  @Output() preferredLocaleChange = new EventEmitter<StoreLocale>();
  private http = inject(HttpClient);
  private locales = inject(LocaleService);
  token = localStorage.getItem('artio-token');
  account: any = null;
  message = '';
  error = '';
  loading = false;
  firstName = '';
  lastName = '';
  currentPassword = '';
  newEmail = '';
  newPassword = '';
  emailToken = '';
  resetEmail = '';
  resetPassword = '';
  resetToken = '';
  preferredLocale: StoreLocale = 'en';
  languageSaving = false;

  ngOnChanges(): void {
    if (this.page === 'account' && this.token && !this.account && !this.loading) this.loadAccount();
    if (this.page === 'reset-password') this.resetToken = new URLSearchParams(location.search).get('token') ?? '';
  }
  t(key: string): string { return LANGUAGE_MESSAGES[this.locale][key] ?? ACCOUNT_MESSAGES[this.locale][key] ?? this.locales.translate(key, this.locale); }
  private loadAccount(): void {
    this.loading = true;
    this.http.get<any>('/api/v1/users/me').subscribe({ next: user => { this.account = user; this.firstName = user.firstName ?? ''; this.lastName = user.lastName ?? ''; this.http.get<{preferredLocale:string|null;defaultLocale:string;availableLanguages:string[]}>('/api/v1/users/me/preferences').subscribe({next:preferences=>{this.availableLanguages=preferences.availableLanguages;this.preferredLocale=this.locales.normalize(preferences.preferredLocale??preferences.defaultLocale);this.loading=false;},error:()=>{this.loading=false;}}); }, error: e => { this.error = this.apiErrorMessage(e, this.t('accountUnavailable')); this.loading = false; } });
  }
  savePreferredLanguage(): void {
    if (this.languageSaving || !this.availableLanguages.includes(this.preferredLocale)) return;
    this.languageSaving = true; this.error = ''; this.message = '';
    this.http.put<{preferredLocale:string}>('/api/v1/users/me/preferences', {locale:this.preferredLocale}).subscribe({
      next: saved => { this.preferredLocale=this.locales.normalize(saved.preferredLocale); this.locales.set(this.preferredLocale); this.preferredLocaleChange.emit(this.preferredLocale); this.message=this.t('languageSaved'); this.languageSaving=false; },
      error: e => { this.error=this.apiErrorMessage(e,this.t('languageUnavailable')); this.languageSaving=false; }
    });
  }
  saveProfile(): void { this.error = ''; this.message = ''; this.http.put<any>('/api/v1/users/me/profile', { firstName: this.firstName, lastName: this.lastName, currentPassword: this.currentPassword }).subscribe({ next: user => { this.account = user; this.currentPassword = ''; this.message = this.t('profileSaved'); }, error: e => this.error = this.apiErrorMessage(e, this.t('profileSaveUnavailable')) }); }
  changePassword(): void { this.error = ''; this.message = ''; this.http.put('/api/v1/users/me/password', { currentPassword: this.currentPassword, newPassword: this.newPassword }).subscribe({ next: () => { localStorage.removeItem('artio-token'); this.token = null; this.message = this.t('passwordChanged'); setTimeout(() => location.assign('/login'), 1200); }, error: e => this.error = this.apiErrorMessage(e, this.t('passwordChangeUnavailable')) }); }
  requestEmailChange(): void { this.error = ''; this.message = ''; this.http.post<any>('/api/v1/users/me/email-change', { newEmail: this.newEmail, currentPassword: this.currentPassword }).subscribe({ next: result => { this.emailToken = result.developmentToken ?? ''; this.currentPassword = ''; this.message = result.message; }, error: e => this.error = this.apiErrorMessage(e, this.t('emailChangeUnavailable')) }); }
  confirmEmailChange(): void { this.error = ''; this.message = ''; this.http.post<any>('/api/v1/auth/email-change/complete', { token: this.emailToken }).subscribe({ next: result => { this.message = result.message; this.emailToken = ''; localStorage.removeItem('artio-token'); this.token = null; setTimeout(() => location.assign('/login'), 1200); }, error: e => this.error = this.apiErrorMessage(e, this.t('emailChangeUnavailable')) }); }
  requestPasswordReset(): void { this.error = ''; this.message = ''; this.http.post<any>('/api/v1/auth/customer-password-reset/request', { email: this.resetEmail }).subscribe({ next: result => { this.resetToken = result.developmentToken ?? ''; this.message = result.message; }, error: e => this.error = this.apiErrorMessage(e, this.t('resetRequestUnavailable')) }); }
  completePasswordReset(): void { this.error = ''; this.message = ''; this.http.post('/api/v1/auth/customer-password-reset/complete', { token: this.resetToken, password: this.resetPassword }).subscribe({ next: () => { this.message = this.t('resetComplete'); this.resetPassword = ''; setTimeout(() => location.assign('/login'), 1400); }, error: e => this.error = this.apiErrorMessage(e, this.t('resetCompleteUnavailable')) }); }
  private apiErrorMessage(error: any, fallback: string): string {
    const codes: Record<number, string> = { 400: 'BAD_REQUEST', 401: 'UNAUTHORIZED', 403: 'FORBIDDEN', 404: 'NOT_FOUND', 409: 'CONFLICT', 422: 'BAD_REQUEST', 429: 'RATE_LIMITED', 503: 'SERVICE_UNAVAILABLE' };
    const code = error?.error?.code ?? codes[error?.status] ?? (error?.status >= 500 ? 'SERVER_ERROR' : undefined);
    if (typeof code === 'string') { const translated = this.locales.translate(`apiError_${code}`, this.locale); if (translated !== `apiError_${code}`) return translated; }
    return error?.error?.message ?? error?.error?.detail ?? fallback;
  }
  rememberReturn(path: string): void { sessionStorage.setItem('artio-return-after-auth', path); }
  logout(): void { localStorage.removeItem('artio-token'); this.signedOut.emit(); location.assign('/'); }
}
