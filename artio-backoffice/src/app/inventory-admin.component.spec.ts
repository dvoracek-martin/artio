import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  it('confirms the exact inventory balance in an accessible modal and keeps API errors visible', async () => {
    const previousShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal');
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true,
      value: vi.fn(function (this: HTMLDialogElement) {
        Object.defineProperty(this, 'open', { configurable: true, value: true });
      })
    });
    try {
      const fixture = TestBed.createComponent(InventoryAdminComponent);
      fixture.detectChanges();
      http.expectOne('/api/v1/warehouses').flush([]);
      http.expectOne('/api/v1/inventory/low-stock').flush([]);
      fixture.detectChanges();

      fixture.componentInstance.selectedWarehouse = { id: 'wh-1', code: 'MAIN', name: 'Main warehouse', location: 'Zurich', active: true };
      fixture.componentInstance.selectedStock = { warehouseId: 'wh-1', sellableId: 'variant-1', onHand: 12, reserved: 5, available: 7 };
      fixture.componentInstance.draft = { sellableId: 'variant-1', quantity: 20, reason: 'Cycle count' };
      fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      http.expectOne('/api/v1/variants/variant-1').flush({ id: 'variant-1', productId: 'product-1', name: 'Widget', sku: 'W-1', active: true });
      fixture.componentInstance.adjust();
      fixture.detectChanges();
      await new Promise(resolve => setTimeout(resolve, 0));
      fixture.detectChanges();

      const dialog = fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
      expect(dialog.open).toBe(true);
      expect(dialog.getAttribute('aria-labelledby')).toBe('inventory-adjust-title');
      expect(dialog.querySelector('#inventory-adjust-title')?.textContent?.trim()).toBe('Set on-hand balance');
      expect(dialog.querySelector('.refund-payment-summary')?.textContent).toContain('20');
      expect(dialog.textContent).toContain('Reserved stock remains committed.');

      fixture.componentInstance.confirmAdjustment();
      fixture.detectChanges();
      expect(dialog.querySelector('[role="status"]')?.textContent?.trim()).toBe('Saving stock adjustment…');
      const update = http.expectOne('/api/v1/warehouses/wh-1/stock/variant-1');
      expect(update.request.body).toEqual({ quantity: 20, reason: 'Cycle count' });
      update.flush({ detail: 'The balance changed on another workstation.' }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(dialog.querySelector('[role="alert"]')?.textContent?.trim()).toBe('The balance changed on another workstation.');
      expect((dialog.querySelector('button[type="submit"]') as HTMLButtonElement).disabled).toBe(false);
    } finally {
      if (previousShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', previousShowModal);
      else delete (HTMLDialogElement.prototype as Partial<HTMLDialogElement>).showModal;
    }
  });
});
