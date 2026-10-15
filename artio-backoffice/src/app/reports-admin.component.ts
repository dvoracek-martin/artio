import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnDestroy, inject } from '@angular/core';
import { Subscription } from 'rxjs';

type FeatureAvailability = { edition: string; state: string; features: string[]; expiresAt: string | null; graceUntil: string | null };
type StandardReport = {
  since: string; until: string; orders: number; activeCustomers: number;
  ordersByStatus: Array<{ status: string; orders: number }>;
  salesByCurrency: Array<{ currency: string; orders: number; customers: number; capturedAmount: number; refundedAmount: number; netCapturedAmount: number }>;
};
type AdvancedReport = {
  since: string; until: string;
  topVariants: Array<{ currency: string; variantId: string; sku: string | null; variantName: string | null; productId: string | null; productName: string | null; unitsOnCapturedOrders: number; recordedLineValue: number; rank: number }>;
  customerLifetimeValues: Array<{ currency: string; customers: number; capturedAmount: number; refundedAmount: number; netCapturedAmount: number; averageNetCapturedValue: number }>;
};

@Component({
  selector: 'artio-reports-admin',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="panel">
      <div class="panel-heading"><div><p class="eyebrow">OPERATIONAL REPORTS</p><h2>Store performance</h2></div>
        <label>Period <select [value]="days" (change)="changeDays($event)"><option *ngFor="let option of periods" [value]="option">Last {{option}} days</option></select></label>
      </div>
      <p class="muted">Order activity follows the order creation date. Each monetary figure stays in its recorded currency.</p>
      <p *ngIf="loading" class="muted" role="status">Loading reports…</p>
      <p *ngIf="error" class="error" role="alert">{{error}}</p>
      <ng-container *ngIf="!loading && report">
        <div class="metrics"><article><span>Orders created</span><strong>{{report.orders}}</strong><small>Selected period</small></article><article><span>Customers active</span><strong>{{report.activeCustomers}}</strong><small>With an order in this period</small></article></div>
        <div class="table-panel"><h3>Captured and refunded by currency</h3>
          <table *ngIf="report.salesByCurrency.length; else noSales"><thead><tr><th>CURRENCY</th><th>ORDERS</th><th>CUSTOMERS</th><th>CAPTURED</th><th>REFUNDED</th><th>NET CAPTURED</th></tr></thead>
            <tbody><tr *ngFor="let row of report.salesByCurrency"><td>{{row.currency}}</td><td>{{row.orders}}</td><td>{{row.customers}}</td><td>{{money(row.capturedAmount,row.currency)}}</td><td>{{money(row.refundedAmount,row.currency)}}</td><td><strong>{{money(row.netCapturedAmount,row.currency)}}</strong></td></tr></tbody></table>
          <ng-template #noSales><p class="empty">No orders were created during this period.</p></ng-template>
        </div>
        <div class="table-panel"><h3>Order status</h3><table *ngIf="report.ordersByStatus.length; else noStatuses"><thead><tr><th>STATUS</th><th>ORDERS</th></tr></thead><tbody><tr *ngFor="let row of report.ordersByStatus"><td>{{row.status}}</td><td>{{row.orders}}</td></tr></tbody></table><ng-template #noStatuses><p class="empty">No order activity to show.</p></ng-template></div>
      </ng-container>
    </section>
    <section class="panel">
      <div class="panel-heading"><div><p class="eyebrow">ENTERPRISE</p><h2>Advanced reports</h2></div><span class="pill" [class.inactive]="!advancedAvailable">{{advancedAvailable?'Available':'Enterprise license required'}}</span></div>
      <p class="muted">Ranked variants cover fully captured orders in the selected period. Customer lifetime values include successful payment history before the end of the period.</p>
      <p *ngIf="!advancedAvailable" class="empty">Advanced reports require the ADVANCED_REPORTING entitlement. Standard reports remain available.</p>
      <p *ngIf="advancedError" class="error" role="alert">{{advancedError}}</p>
      <ng-container *ngIf="advancedAvailable && advanced">
        <div class="table-panel"><h3>Top variants by currency</h3><table *ngIf="advanced.topVariants.length; else noVariants"><thead><tr><th>RANK</th><th>PRODUCT / VARIANT</th><th>SKU</th><th>CURRENCY</th><th>UNITS</th><th>RECORDED LINE VALUE</th></tr></thead><tbody><tr *ngFor="let row of advanced.topVariants"><td>{{row.rank}}</td><td>{{row.productName||row.productId||'Historical product'}} · {{row.variantName||row.variantId}}</td><td>{{row.sku||'—'}}</td><td>{{row.currency}}</td><td>{{row.unitsOnCapturedOrders}}</td><td>{{money(row.recordedLineValue,row.currency)}}</td></tr></tbody></table><ng-template #noVariants><p class="empty">No fully captured positive value orders in this period.</p></ng-template><small class="muted">Recorded line value uses the original stored line prices and quantities. It is not net captured revenue and does not allocate refunds.</small></div>
        <div class="table-panel"><h3>Customer lifetime value by currency</h3><table *ngIf="advanced.customerLifetimeValues.length; else noLifetime"><thead><tr><th>CURRENCY</th><th>CUSTOMERS</th><th>CAPTURED</th><th>REFUNDED</th><th>NET CAPTURED</th><th>AVERAGE NET</th></tr></thead><tbody><tr *ngFor="let row of advanced.customerLifetimeValues"><td>{{row.currency}}</td><td>{{row.customers}}</td><td>{{money(row.capturedAmount,row.currency)}}</td><td>{{money(row.refundedAmount,row.currency)}}</td><td>{{money(row.netCapturedAmount,row.currency)}}</td><td>{{money(row.averageNetCapturedValue,row.currency)}}</td></tr></tbody></table><ng-template #noLifetime><p class="empty">No successful payment history is available before the period end.</p></ng-template></div>
      </ng-container>
    </section>`
})
export class ReportsAdminComponent implements OnDestroy {
  private readonly http = inject(HttpClient);
  private request?: Subscription;
  private generation = 0;
  readonly periods = [7, 30, 90, 365];
  days = 30;
  loading = false;
  error = '';
  advancedError = '';
  availability: FeatureAvailability | null = null;
  report: StandardReport | null = null;
  advanced: AdvancedReport | null = null;

  get advancedAvailable(): boolean { return this.availability?.features.includes('ADVANCED_REPORTING') ?? false; }

  constructor() { this.load(); }
  ngOnDestroy(): void { this.generation++; this.request?.unsubscribe(); }

  changeDays(event: Event): void {
    const days = Number((event.target as HTMLSelectElement).value);
    if (!this.periods.includes(days)) return;
    this.days = days;
    this.load();
  }

  money(amount: number, currency: string): string {
    try { return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount); }
    catch { return `${amount} ${currency}`; }
  }

  private load(): void {
    const generation = ++this.generation;
    this.request?.unsubscribe();
    this.loading = true;
    this.error = '';
    this.advancedError = '';
    this.report = null;
    this.advanced = null;
    this.request = this.http.get<FeatureAvailability>('/api/v1/admin/license/availability').subscribe({
      next: availability => {
        if (generation !== this.generation) return;
        this.availability = availability;
        this.loadStandard(generation);
        if (this.advancedAvailable) this.loadAdvanced(generation);
      },
      error: error => {
        if (generation !== this.generation) return;
        this.loading = false;
        this.error = error.status === 401 || error.status === 403 ? 'Administrator access is required to view reports.' : 'Could not load edition availability.';
      }
    });
  }

  private loadStandard(generation: number): void {
    this.http.get<StandardReport>(`/api/v1/admin/reports?days=${this.days}`).subscribe({
      next: report => { if (generation === this.generation) { this.report = report; this.loading = false; } },
      error: error => { if (generation === this.generation) { this.loading = false; this.error = error.error?.message ?? 'Could not load standard reports.'; } }
    });
  }

  private loadAdvanced(generation: number): void {
    this.http.get<AdvancedReport>(`/api/v1/admin/reports/advanced?days=${this.days}`).subscribe({
      next: report => { if (generation === this.generation) this.advanced = report; },
      error: error => { if (generation === this.generation) this.advancedError = error.error?.message ?? 'Advanced reports are currently unavailable.'; }
    });
  }
}
