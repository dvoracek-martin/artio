import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { BackofficeLocale } from './backoffice-locale';
import { OrdersAdminComponent } from './orders-admin.component';

describe('OrdersAdminComponent', () => {
  beforeEach(async () => TestBed.configureTestingModule({ imports: [OrdersAdminComponent] }).compileComponents());
  afterEach(() => localStorage.removeItem('artio-backoffice-language'));

  it.each([
    ['en', 'Search, review and fulfil customer orders.', 'All statuses', 'No orders match these filters.'],
    ['de', 'Bestellungen suchen, prüfen und bearbeiten.', 'Alle Status', 'Keine Bestellungen entsprechen diesen Filtern.'],
    ['it', 'Cerca, controlla ed evadi gli ordini dei clienti.', 'Tutti gli stati', 'Nessun ordine corrisponde a questi filtri.'],
    ['fr', 'Rechercher, vérifier et traiter les commandes clients.', 'Tous les statuts', 'Aucune commande ne correspond à ces filtres.'],
  ] as const)('renders order loading and empty states in %s', (locale, intro, statuses, empty) => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    fixture.componentInstance.locale = locale as BackofficeLocale;
    fixture.componentInstance.loading = false;
    fixture.componentInstance.statuses = ['PENDING', 'PARTIALLY_SHIPPED'];
    fixture.detectChanges();
    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain(intro);
    expect(page).toContain(statuses);
    expect(page).toContain(empty);
    expect(fixture.nativeElement.querySelector('[aria-label]')).not.toBeNull();
  });

  it('forwards typed order actions and shipment quantities to the parent', () => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    const actions: string[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action.type));
    fixture.componentInstance.selectedOrder = { lines: [{ id: 'line-1', shipQuantity: 2 }, { id: 'line-2', shipQuantity: 0 }] };
    fixture.componentInstance.trackingNumber = 'TRACK-1';
    fixture.componentInstance.createShipment();
    fixture.componentInstance.returnAction({ id: 'return-1' }, 'review', true, false);
    expect(actions).toEqual(['createShipment', 'return']);
  });
});
