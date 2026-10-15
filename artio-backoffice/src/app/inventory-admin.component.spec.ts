import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setBackofficeLocale, type BackofficeLocale } from './backoffice-locale';
import { InventoryAdminComponent } from './inventory-admin.component';

describe('InventoryAdminComponent', () => {
  let http: HttpTestingController;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [InventoryAdminComponent], providers: [provideHttpClient(), provideHttpClientTesting()] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); localStorage.removeItem('artio-backoffice-language'); });

  it.each([
    ['en', 'Warehouses', 'Loading warehouses…', 'Create a warehouse to start tracking inventory.'],
    ['de', 'Lager', 'Lager werden geladen…', 'Erstellen Sie ein Lager, um den Bestand zu verfolgen.'],
    ['it', 'Magazzini', 'Caricamento magazzini…', 'Crea un magazzino per iniziare a monitorare le scorte.'],
    ['fr', 'Entrepôts', 'Chargement des entrepôts…', 'Créez un entrepôt pour commencer à suivre le stock.'],
  ] as const)('localizes inventory loading and empty states in %s', (locale, title, loading, empty) => {
    setBackofficeLocale(locale as BackofficeLocale);
    const fixture = TestBed.createComponent(InventoryAdminComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(loading);
    http.expectOne('/api/v1/warehouses').flush([]);
    http.expectOne('/api/v1/inventory/low-stock').flush([]);
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(title);
    expect(page).toContain(empty);
    expect(fixture.nativeElement.querySelector('[aria-label]')).not.toBeNull();
  });
});
