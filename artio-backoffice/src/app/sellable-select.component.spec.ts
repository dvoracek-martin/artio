import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { SellableSelectComponent } from './sellable-select.component';

describe('SellableSelectComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SellableSelectComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-backoffice-language');
  });

  it.each([
    ['en', 'Could not load product variants.', 'Retry', 'Search products or SKUs'],
    ['de', 'Produktvarianten konnten nicht geladen werden.', 'Erneut versuchen', 'Produkte oder SKUs suchen'],
    ['it', 'Impossibile caricare le varianti del prodotto.', 'Riprova', 'Cerca prodotti o SKU'],
    ['fr', 'Impossible de charger les variantes du produit.', 'Réessayer', 'Rechercher des produits ou des SKU']
  ] as const)('localizes variant lookup help, failure and retry in %s', async (locale, failure, retry, placeholder) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(SellableSelectComponent);
    fixture.detectChanges();
    await new Promise(resolve => setTimeout(resolve, 225));
    http.expectOne(request => request.url === '/api/v1/admin/catalog/products').flush({}, { status: 503, statusText: 'Service unavailable' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.picker-error')?.textContent).toContain(failure);
    expect(fixture.nativeElement.querySelector('.picker-error button')?.textContent).toContain(retry);
    expect(fixture.nativeElement.querySelector('.search-select-trigger')?.textContent).toContain(placeholder);
  });
});
