import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { GiftCardAdminComponent } from './gift-card-admin.component';

describe('GiftCardAdminComponent', () => {
  let http: HttpTestingController;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [GiftCardAdminComponent], providers: [provideHttpClient(), provideHttpClientTesting()] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); localStorage.removeItem('artio-backoffice-language'); });

  it.each([
    ['en', 'Issue a gift card', 'Loading gift cards…', 'No gift cards have been issued.'],
    ['de', 'Geschenkkarte ausstellen', 'Geschenkkarten werden geladen…', 'Es wurden noch keine Geschenkkarten ausgestellt.'],
    ['it', 'Emetti una gift card', 'Caricamento gift card…', 'Non è ancora stata emessa alcuna gift card.'],
    ['fr', 'Émettre une carte cadeau', 'Chargement des cartes cadeaux…', 'Aucune carte cadeau n’a encore été émise.'],
  ] as const)('localizes gift card loading and empty states in %s', (locale, title, loading, empty) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(GiftCardAdminComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(loading);
    http.expectOne('/api/v1/admin/gift-cards').flush([]);
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(title);
    expect(page).toContain(empty);
    expect(page).toContain(locale === 'en' ? 'Initial amount' : locale === 'de' ? 'Anfangsbetrag' : locale === 'it' ? 'Importo iniziale' : 'Montant initial');
  });
});
