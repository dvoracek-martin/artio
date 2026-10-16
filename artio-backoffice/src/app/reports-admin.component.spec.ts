import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ReportsAdminComponent } from './reports-admin.component';

describe('ReportsAdminComponent', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReportsAdminComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    localStorage.removeItem('artio-backoffice-language');
  });

  it('loads standard community reports and keeps advanced reports behind the entitlement', () => {
    const fixture = TestBed.createComponent(ReportsAdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/license/availability').flush({ edition: 'COMMUNITY', state: 'ACTIVE', features: [], expiresAt: null, graceUntil: null });
    http.expectOne('/api/v1/admin/reports?days=30').flush({
      since: '2026-09-01', until: '2026-10-01', orders: 4, activeCustomers: 3, ordersByStatus: [], salesByCurrency: [],
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('4');
    expect(fixture.nativeElement.textContent).toContain('Enterprise license required');
    http.expectNone('/api/v1/admin/reports/advanced?days=30');
  });

  it('requests advanced report data only when licensed and renders net captured values', () => {
    const fixture = TestBed.createComponent(ReportsAdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/license/availability').flush({ edition: 'ENTERPRISE', state: 'ACTIVE', features: ['ADVANCED_REPORTING'], expiresAt: null, graceUntil: null });
    http.expectOne('/api/v1/admin/reports?days=30').flush({
      since: '2026-09-01', until: '2026-10-01', orders: 2, activeCustomers: 1, ordersByStatus: [], salesByCurrency: [],
    });
    http.expectOne('/api/v1/admin/reports/advanced?days=30').flush({
      since: '2026-09-01', until: '2026-10-01',
      topVariants: [{ currency: 'CHF', variantId: 'variant-1', sku: 'SKU-1', variantName: 'Blue', productId: 'product-1', productName: 'Bag', unitsOnCapturedOrders: 2, recordedLineValue: 42, rank: 1 }],
      customerLifetimeValues: [{ currency: 'CHF', customers: 1, capturedAmount: 50, refundedAmount: 5, reversedAmount: 0, netCapturedAmount: 45, averageNetCapturedValue: 45 }],
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Bag');
    expect(fixture.nativeElement.textContent).toContain('Blue');
    expect(fixture.nativeElement.textContent).toContain('Customer lifetime value by currency');
    expect(fixture.componentInstance.advancedAvailable).toBe(true);
  });

  it('shows a localized authorization error when license availability is forbidden', () => {
    const fixture = TestBed.createComponent(ReportsAdminComponent);
    fixture.detectChanges();
    http.expectOne('/api/v1/admin/license/availability').flush({}, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Administrator access is required');
    http.expectNone('/api/v1/admin/reports?days=30');
  });
});
