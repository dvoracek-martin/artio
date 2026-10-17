import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { SetupWizardComponent } from './setup-wizard.component';

describe('SetupWizardComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({
    imports: [SetupWizardComponent],
    providers: [provideHttpClient()],
  }).compileComponents());
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Installation progress'],
    ['de', 'Einrichtungsfortschritt'],
    ['it', 'Avanzamento configurazione'],
    ['fr', 'Progression de l’installation'],
  ] as const)('localizes the accessible step-navigation name in %s', (locale, progressLabel) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(SetupWizardComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.setup-navigation')?.getAttribute('aria-label')).toBe(progressLabel);
    fixture.destroy();
  });
});
