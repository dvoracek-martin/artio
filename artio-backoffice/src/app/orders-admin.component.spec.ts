import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import type { BackofficeLocale } from './backoffice-locale';
import { OrdersAdminComponent } from './orders-admin.component';

describe('OrdersAdminComponent', () => {
  let http: HttpTestingController;
  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [OrdersAdminComponent], providers: [provideHttpClient(), provideHttpClientTesting()] }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    for (const request of http.match('/api/v1/admin/payments/reconciliation-cases')) request.flush([]);
    http.verify();
    localStorage.removeItem('artio-backoffice-language');
  });

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

  it('shows reconciliation facts as a read-only queue and opens the affected order', async () => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    const actions: unknown[] = [];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/payments/reconciliation-cases').flush([{ caseId: 'case-1', paymentId: 'payment-1', orderId: 'order-1', type: 'OVERPAYMENT', expectedAmount: '20.00', capturedAmount: '25.00', expectedCurrency: 'CHF', capturedCurrency: 'CHF', status: 'OPEN', createdAt: '2026-10-17T08:00:00Z' }]);
    await fixture.whenStable();
    fixture.detectChanges();

    const page = fixture.nativeElement.textContent as string;
    expect(page).toContain('Payment captures requiring review');
    expect(page).toContain('Overpayment');
    expect(page).toContain('Expected 20.00 CHF · Captured 25.00 CHF');
    expect(page).toContain('This queue is read-only');
    [...fixture.nativeElement.querySelectorAll('button')]
      .find((button: HTMLButtonElement) => button.textContent?.trim() === 'Open order')?.click();
    expect(actions).toEqual([{ type: 'view', order: { id: 'order-1' } }]);
  });

  it.each([
    ['en', 'Payment captures requiring review', 'No payment cases need review.'],
    ['de', 'Zu prüfende Zahlungseingänge', 'Keine Zahlungsfälle müssen geprüft werden.'],
    ['it', 'Incassi da verificare', 'Nessun caso di pagamento da verificare.'],
    ['fr', 'Encaissements à vérifier', 'Aucun cas de paiement à vérifier.'],
  ] as const)('renders reconciliation empty and error states in %s', async (locale, title, empty) => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    fixture.componentInstance.locale = locale as BackofficeLocale;
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/payments/reconciliation-cases').flush({ message: 'Case queue unavailable' }, { status: 503, statusText: 'Unavailable' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain(title);
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Case queue unavailable');
    const emptyFixture = TestBed.createComponent(OrdersAdminComponent);
    emptyFixture.componentInstance.locale = locale as BackofficeLocale;
    emptyFixture.detectChanges();
    http.expectOne('/api/v1/admin/payments/reconciliation-cases').flush([]);
    await emptyFixture.whenStable();
    emptyFixture.detectChanges();
    expect(emptyFixture.nativeElement.textContent).toContain(empty);
  });

  it('renders an accessible refund action bound to the selected captured payment', () => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    const payment = { id: 'payment-1', method: 'card', status: 'CAPTURED', providerReference: 'provider-1', refundedAmount: '0.00', amount: '25.00', currency: 'CHF' };
    const actions: unknown[] = [];
    fixture.componentInstance.selectedOrder = {
      id: 'order-1', status: 'PAID', customerEmail: 'ada@example.test', createdAt: '2026-01-01T00:00:00Z',
      subtotal: '25.00', tax: '0.00', shipping: '0.00', total: '25.00', currency: 'CHF',
      shippingAddress: '', billingAddress: '', lines: [], payments: [payment],
    };
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();

    const refundButton = [...fixture.nativeElement.querySelectorAll('button')]
      .find(button => (button.textContent as string).trim() === 'Refund');
    expect(refundButton).toBeDefined();
    expect(refundButton?.getAttribute('aria-label')).toBeNull();
    refundButton?.click();

    expect(actions).toEqual([{ type: 'refund', payment }]);
  });

  it('binds return approval and rejection buttons to the displayed return request', () => {
    const fixture = TestBed.createComponent(OrdersAdminComponent);
    const returnRequest = { id: 'return-1', status: 'REQUESTED', reason: 'wrong size', createdAt: '2026-01-01T00:00:00Z', items: [] };
    const actions: unknown[] = [];
    fixture.componentInstance.selectedOrder = {
      id: 'order-1', status: 'PAID', customerEmail: 'ada@example.test', createdAt: '2026-01-01T00:00:00Z',
      subtotal: '25.00', tax: '0.00', shipping: '0.00', total: '25.00', currency: 'CHF',
      shippingAddress: '', billingAddress: '', lines: [], payments: [],
    };
    fixture.componentInstance.returns = [returnRequest];
    fixture.componentInstance.action.subscribe(action => actions.push(action));
    fixture.detectChanges();

    const buttons = [...fixture.nativeElement.querySelectorAll('button')];
    expect(buttons.map(button => (button.textContent as string).trim())).toContain('Approve');
    expect(buttons.map(button => (button.textContent as string).trim())).toContain('Reject');
    buttons.find(button => (button.textContent as string).trim() === 'Approve')?.click();
    buttons.find(button => (button.textContent as string).trim() === 'Reject')?.click();

    expect(actions).toEqual([
      { type: 'return', item: returnRequest, action: 'review', approve: true, restock: false },
      { type: 'return', item: returnRequest, action: 'review', approve: false, restock: false },
    ]);
  });
});
