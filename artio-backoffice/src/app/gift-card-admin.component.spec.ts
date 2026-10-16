import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  it('opens a labelled status modal and keeps a failed gift-card update retryable', async () => {
    const previousShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        Object.defineProperty(this, 'open', { configurable: true, value: true });
      })
    });
    try {
      const fixture = TestBed.createComponent(GiftCardAdminComponent);
      fixture.detectChanges();
      http.expectOne('/api/v1/admin/gift-cards').flush([]);
      const card = { id: 'card-1', initialBalance: 25, balance: 18, currency: 'CHF', expiresAt: null, active: true, createdAt: '2026-10-16T10:00:00Z' };
      fixture.componentInstance.cards = [card];
      fixture.componentInstance.toggle(card);
      fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      fixture.detectChanges();

      const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.open).toBe(true);
      expect(dialog.getAttribute('aria-labelledby')).toBe('gift-card-status-title');
      expect(dialog.querySelector('#gift-card-status-title')?.textContent?.trim()).toBe('Disable gift card');
      expect(dialog.textContent).toContain('A disabled card cannot be redeemed or refunded until it is enabled again.');
      expect(dialog.querySelector('.refund-payment-summary')?.textContent).toContain('18 CHF');

      fixture.componentInstance.submitStatusChange();
      fixture.detectChanges();
      expect(dialog.querySelector('[role="status"]')?.textContent?.trim()).toBe('Updating gift card…');
      const update = http.expectOne('/api/v1/admin/gift-cards/card-1/active');
      expect(update.request.body).toEqual({ active: false });
      update.flush({ detail: 'Gift card is referenced by a pending obligation.' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(dialog.querySelector('[role="alert"]')?.textContent?.trim()).toBe('Gift card is referenced by a pending obligation.');
      expect((dialog.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
    } finally {
      if (previousShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', previousShowModal);
      else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    }
  });
});
