
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, OnInit, Output, inject, ChangeDetectionStrategy } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { COUNTRY_OPTIONS, CURRENCY_OPTIONS, LANGUAGE_OPTIONS, LOCALE_OPTIONS, TIMEZONE_OPTIONS } from './commerce-options';
import { SearchSelectComponent } from './search-select.component';
import { BackofficeLocale, currentBackofficeLocale, setBackofficeLocale } from './backoffice-locale';
import { SetupTextKey, setupText } from './setup-wizard-locale';
import { firstValueFrom } from 'rxjs';

type SetupDraft = { storeName: string; countryCode: string; currencyCode: string; locale: string; timezone: string; availableLanguages: string; externalBaseUrl: string; email: string; firstName: string; lastName: string };
@Component({
    selector: 'artio-setup-wizard', imports: [FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    templateUrl: './setup-wizard.component.html'
})
export class SetupWizardComponent implements OnInit {
  @Input() developerMode = false;
  @Output() completed = new EventEmitter<{ email: string; demoCredentials: boolean }>();
  private http = inject(HttpClient);
  get steps():string[]{return [this.t('stepWelcome'),this.t('stepStore'),this.t('stepRegion'),this.t('stepOwner'),this.t('stepReview')]}
  get descriptions():string[]{return [this.t('descWelcome'),this.t('descStore'),this.t('descRegion'),this.t('descOwner'),this.t('descReview')]}
  t(key:SetupTextKey):string{return setupText(key)}
  get currentLocale():BackofficeLocale{return currentBackofficeLocale()}
  selectLocale(value:string):void{if(value==='de'||value==='it'||value==='fr'||value==='en')setBackofficeLocale(value)}
  step = 0;
  busy = false;
  error = '';
  saved = false;
  token = '';
  password = '';
  confirmation = '';
  showPassword = false;
  draft: SetupDraft = { storeName: 'Artio Store', countryCode: 'CH', currencyCode: 'CHF', locale: 'de-CH', timezone: 'Europe/Zurich', availableLanguages: 'de,fr,it,en', externalBaseUrl: '', email: '', firstName: '', lastName: '' };
  languageOptions=LANGUAGE_OPTIONS;
  localeOptions=LOCALE_OPTIONS;
  countryOptions=COUNTRY_OPTIONS;
  currencyOptions=CURRENCY_OPTIONS;
  timezoneOptions=TIMEZONE_OPTIONS;
  private languageSelection=['de','fr','it','en'];
  get selectedLanguages():string[]{return this.languageSelection;}
  set selectedLanguages(value:string[]){this.languageSelection=value;this.draft.availableLanguages=value.join(',');}
  ngOnInit(): void {
    if (this.developerMode) {
      this.token = 'ArtioLocalDevSetupTokenOnly2026!';
      this.password = this.confirmation = 'ArtioLocalAdmin!2026';
      this.draft = { ...this.draft, storeName: 'Artio Development Store', externalBaseUrl: 'http://localhost:4200', email: 'admin@example.test', firstName: 'Alex', lastName: 'Developer' };
    }
  }
  get progress(): number { return this.step === 5 ? 100 : (this.step + 1) * 20; }
  get languages(): string[] { return this.draft.availableLanguages.split(',').map(x => x.trim()).filter(Boolean); }
  private focus(): void { setTimeout(() => document.getElementById('setup-step-heading')?.focus()); }
  back(): void { if (!this.busy && this.step > 0) { this.step--; this.error = ''; this.focus(); } }
  edit(step: number): void { if (!this.busy) { this.step = step; this.error = ''; this.focus(); } }
  async next(): Promise<void> {
    if (this.busy || !this.validate(this.step)) return;
    this.busy = true;
    try {
      if (this.step === 0) {
        const status = await firstValueFrom(this.http.post<{ required: boolean; draft: Partial<SetupDraft> }>('/api/v1/setup/resume', {}, { headers: this.headers() }));
        if (!status.required) { this.password=this.confirmation=this.token=''; this.step = 5; this.focus(); return; }
        // Resume only before editing; never replace current fields during final submission.
        this.draft = { ...this.draft, ...status.draft };
        this.languageSelection = this.languages;
        this.saved = Object.keys(status.draft ?? {}).length > 0;
      }
      if (this.step === 3) { await this.saveDraft(); }
      this.step++;
      this.focus();
    } catch (e) { this.fail(e, this.t('errorProgress')); }
    finally { this.busy = false; }
  }
  private async saveDraft(): Promise<void> {
    const draft = Object.fromEntries(Object.entries(this.draft).map(([key, value]) => [key, value.trim()]));
    await firstValueFrom(this.http.post('/api/v1/setup/progress', draft, { headers: this.headers() }));
    this.saved = true;
  }
  async launch(): Promise<void> {
    if (this.busy) return;
    for (let step = 1; step <= 3; step++) if (!this.validate(step)) { this.step = step; this.focus(); return; }
    this.busy = true;
    this.error = '';
    try {
      await this.saveDraft();
      await firstValueFrom(this.http.post('/api/v1/setup/complete', { password: this.password }, { headers: this.headers() }));
      this.demoCredentials = this.developerMode && this.draft.email.trim() === 'admin@example.test' && this.password === 'ArtioLocalAdmin!2026';
      this.password = this.confirmation = this.token = '';
      this.step = 5;
      this.focus();
    } catch (e) { this.fail(e, this.t('errorComplete')); }
    finally { this.busy = false; }
  }
  private demoCredentials = false;
  finish(): void { this.completed.emit({ email: this.draft.email.trim(), demoCredentials: this.demoCredentials }); }
  private headers(): Record<string, string> { return { 'X-Artio-Setup-Token': this.token }; }
  private fail(error: unknown, fallback: string): void {
    this.error = error instanceof HttpErrorResponse ? error.error?.message ?? error.error?.detail ?? (error.status === 429 ? this.t('errorLocked') : fallback) : fallback;
    setTimeout(() => document.getElementById('setup-error')?.focus());
  }
  validate(step: number): boolean {
    this.error = '';
    if (step === 0 && !this.token.trim()) this.error = this.t('errorToken');
    if (step === 1) {
      if (!this.draft.storeName.trim() || this.draft.storeName.trim().length > 100) this.error = this.t('errorStoreName');
      if (this.draft.externalBaseUrl.trim()) {
        try {
          const url = new URL(this.draft.externalBaseUrl.trim());
          const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
          if (!(url.protocol === 'https:' || (url.protocol === 'http:' && local)) || url.username || url.password || url.search || url.hash) throw new Error();
        } catch { this.error = this.t('errorStoreUrl'); }
      }
    }
    if (step === 2) {
      try {
        if (!/^[A-Z]{2}$/.test(this.draft.countryCode) || !/^[A-Z]{3}$/.test(this.draft.currencyCode)) throw new Error();
        new Intl.DisplayNames(['en'], { type: 'region' }).of(this.draft.countryCode);
        new Intl.NumberFormat('en', { style: 'currency', currency: this.draft.currencyCode });
        new Intl.DateTimeFormat(this.draft.locale, { timeZone: this.draft.timezone });
        if (!this.languages.length || this.languages.length > 32) throw new Error();
        this.languages.forEach(tag => { if (tag === 'und') throw new Error(); Intl.getCanonicalLocales(tag); });
      } catch { this.error = this.t('errorRegion'); }
    }
    if (step === 3) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.draft.email.trim())) this.error = this.t('errorEmail');
      else if (![this.draft.firstName, this.draft.lastName].every(x => x.trim() && x.trim().length <= 100)) this.error = this.t('errorName');
      else if (this.password.length < 8 || this.password.length > 72) this.error = this.t('errorPassword');
      else if (this.password !== this.confirmation) this.error = this.t('errorPasswordMatch');
    }
    if (this.error) setTimeout(() => document.getElementById('setup-error')?.focus());
    return !this.error;
  }
}
