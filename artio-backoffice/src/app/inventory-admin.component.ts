import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy } from '@angular/core';
import { SellableSelectComponent } from './sellable-select.component';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { FormsModule } from '@angular/forms';

type Warehouse = { id: string; code: string; name: string; location: string; active: boolean };
type Stock = { warehouseId: string; sellableId: string; onHand: number; reserved: number; available: number };
type Movement = { id: string; type: string; onHandDelta: number; reservedDelta: number; onHandAfter: number; reservedAfter: number; reason: string | null; actorId: string | null; occurredAt: string };
type LowStock = { warehouseId: string; sellableId: string; available: number; threshold: number };

@Component({
    selector: 'artio-inventory-admin',
    imports: [CommonModule, FormsModule, SellableSelectComponent, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section>
      @if (lowStock.length) {
        <div class="panel">
          <div class="panel-heading"><div><p class="eyebrow">REPLENISHMENT</p><h2>Low stock</h2></div><button class="link-button" (click)="loadLowStock()">Refresh</button></div>
          @for (row of lowStock; track row) {
            <div class="row"><span class="row-main"><strong>{{row.sellableId}}</strong><small>{{warehouseName(row.warehouseId)}} · {{row.available}} available · threshold {{row.threshold}}</small></span><button class="link-button" (click)="openLowStock(row)">Manage</button></div>
          }
        </div>
      }
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">STOCK LOCATIONS</p><h2>Warehouses</h2></div><button class="link-button" (click)="loadWarehouses()">Refresh</button></div>
        <form class="create-form" (ngSubmit)="createWarehouse()">
          <input [(ngModel)]="warehouseDraft.code" name="warehouseCode" maxlength="32" placeholder="Code · e.g. ZH-MAIN" required>
          <input [(ngModel)]="warehouseDraft.name" name="warehouseName" maxlength="200" placeholder="Warehouse name" required>
          <input [(ngModel)]="warehouseDraft.location" name="warehouseLocation" maxlength="300" placeholder="Location" required>
          <button class="primary" [disabled]="busy">Add warehouse</button>
        </form>
        @for (warehouse of warehouses; track warehouse) {
          <div class="row">
            <span class="row-main"><strong>{{warehouse.name}} · {{warehouse.code}}</strong><small>{{warehouse.location}} · {{warehouse.active?'Active':'Inactive'}}</small></span>
            <button class="link-button" (click)="selectWarehouse(warehouse)">{{selectedWarehouse?.id===warehouse.id?'Selected':'View stock'}}</button>
          </div>
        }
        @if (warehouses.length) {
          <div class="setup-field"><span>Jump to warehouse</span><artio-search-select [ngModel]="selectedWarehouse?.id || ''" (ngModelChange)="chooseWarehouse($event)" label="Warehouse" [options]="warehouseOptions" placeholder="Choose a warehouse"></artio-search-select></div>
          }@if (!warehouses.length) {
          <p class="empty">Create a warehouse to start tracking inventory.</p>
        }
      </div>

      @if (selectedWarehouse) {
        <div class="panel table-panel">
          <div class="panel-heading"><div><p class="eyebrow">{{selectedWarehouse.code}}</p><h2>{{selectedWarehouse.name}} stock</h2></div><button class="link-button" (click)="loadStock()">Refresh</button></div>
          <table><thead><tr><th>SELLABLE ID</th><th>ON HAND</th><th>RESERVED</th><th>AVAILABLE</th><th>ACTION</th></tr></thead><tbody>
          @for (row of stocks; track row) {
            <tr><td><code>{{row.sellableId}}</code></td><td>{{row.onHand}}</td><td>{{row.reserved}}</td><td>{{row.available}}</td><td><button class="link-button" (click)="manage(row)">Receive / adjust / history</button></td></tr>
          }
        </tbody></table>@if (!stocks.length) {
        <p class="empty">No stock records in this warehouse yet.</p>
      }
      <form class="create-form" (ngSubmit)="receive()"><artio-sellable-select [(ngModel)]="draft.sellableId" name="newSellableId" label="Stock receipt product variant"></artio-sellable-select><input [(ngModel)]="draft.quantity" name="newQuantity" type="number" min="0.0001" step="0.0001" placeholder="Receipt quantity" required><input [(ngModel)]="draft.reason" name="newReceiptReason" maxlength="500" placeholder="Receipt note (optional)"><button class="primary" [disabled]="busy">Receive stock</button></form>
    </div>
    @if (selectedStock) {
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">STOCK CONTROL</p><h2>{{selectedStock.sellableId}}</h2></div><button class="link-button" (click)="selectedStock=null">Close</button></div>
        <p>Current: {{selectedStock.onHand}} on hand · {{selectedStock.reserved}} reserved · {{selectedStock.available}} available</p>
        <div class="create-form"><input [(ngModel)]="draft.quantity" name="managedQuantity" type="number" min="0" step="0.0001" placeholder="Quantity" required><input [(ngModel)]="draft.reason" name="adjustmentReason" maxlength="500" placeholder="Required adjustment reason"><button class="link-button" (click)="receiveManaged()">Receive this quantity</button><button class="primary" (click)="adjust()">Set on-hand quantity</button></div>
        <h3>Movement history</h3>
        @for (item of movements; track item) {
          <div class="row"><span class="row-main"><strong>{{item.type}} · Δ on hand {{item.onHandDelta}} · Δ reserved {{item.reservedDelta}}</strong><small>{{item.occurredAt | date:'medium'}} · now {{item.onHandAfter}} on hand / {{item.reservedAfter}} reserved · {{item.reason||'System movement'}}{{item.actorId?' · staff '+item.actorId:''}}</small></span></div>
        }
        @if (!movements.length) {
          <p class="empty">No stock movements recorded.</p>
        }
        <h3>Low-stock alert</h3>
        <form class="create-form" (ngSubmit)="saveLowStockPolicy()"><input [(ngModel)]="policyDraft.threshold" name="lowStockThreshold" type="number" min="0" step="0.0001" placeholder="Alert when available is at or below" required><label><input type="checkbox" [(ngModel)]="policyDraft.active" name="lowStockActive"> Enabled</label><button class="primary" [disabled]="busy">Save threshold</button></form>
      </div>
    }
    }
    @if (error) {
      <p class="error" role="alert">{{error}}</p>
    }
    </section>
    `
})
export class InventoryAdminComponent {
  private readonly http = inject(HttpClient);
  warehouses: Warehouse[] = [];
  stocks: Stock[] = [];
  movements: Movement[] = [];
  lowStock: LowStock[] = [];
  policyDraft = { threshold: 0, active: true };
  selectedWarehouse: Warehouse | null = null;
  selectedStock: Stock | null = null;
  warehouseDraft = { code: '', name: '', location: '' };
  draft = { sellableId: '', quantity: 1, reason: '' };
  busy = false;
  error = '';

  get warehouseOptions():SelectOption[]{return this.warehouses.map(x=>({value:x.id,label:x.name,detail:x.code+' · '+x.location,disabled:!x.active}));}
  chooseWarehouse(id:string):void{const row=this.warehouses.find(x=>x.id===id);if(row)this.selectWarehouse(row);}
  constructor() { this.loadWarehouses(); this.loadLowStock(); }

  loadLowStock(): void { this.http.get<LowStock[]>('/api/v1/inventory/low-stock').subscribe({next:rows=>this.lowStock=rows,error:e=>this.error=e.error?.detail??'Could not load low-stock alerts.'}); }
  warehouseName(id:string):string{return this.warehouses.find(row=>row.id===id)?.name??id;}
  openLowStock(row:LowStock):void{const warehouse=this.warehouses.find(item=>item.id===row.warehouseId);if(!warehouse)return;this.selectedWarehouse=warehouse;this.loadStock();this.http.get<Array<{sellableId:string;threshold:number;active:boolean}>>(`/api/v1/warehouses/${warehouse.id}/stock-policies`).subscribe({next:policies=>{const policy=policies.find(item=>item.sellableId===row.sellableId);this.policyDraft=policy?{threshold:policy.threshold,active:policy.active}:{threshold:row.threshold,active:true};this.selectedStock={warehouseId:row.warehouseId,sellableId:row.sellableId,onHand:0,reserved:0,available:row.available};this.loadMovements();},error:e=>this.error=e.error?.detail??'Could not load stock policies.'});}
  saveLowStockPolicy():void{if(!this.selectedWarehouse||!this.selectedStock)return;this.busy=true;this.http.put(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${this.selectedStock.sellableId}/low-stock-policy`,this.policyDraft).subscribe({next:()=>{this.busy=false;this.error='';this.loadLowStock();},error:e=>{this.busy=false;this.error=e.error?.detail??'Could not save low-stock threshold.';}});}

  loadWarehouses(): void {
    this.http.get<Warehouse[]>('/api/v1/warehouses').subscribe({
      next: rows => { this.warehouses = rows; if (this.selectedWarehouse) this.selectedWarehouse = rows.find(row => row.id === this.selectedWarehouse?.id) ?? null; },
      error: e => this.error = e.error?.detail ?? 'Could not load warehouses.'
    });
  }

  createWarehouse(): void {
    this.busy = true;
    this.http.post<Warehouse>('/api/v1/warehouses', { ...this.warehouseDraft, code: this.warehouseDraft.code.trim().toUpperCase() }).subscribe({
      next: row => { this.warehouseDraft = { code: '', name: '', location: '' }; this.busy = false; this.loadWarehouses(); this.selectWarehouse(row); },
      error: e => { this.error = e.error?.detail ?? 'Could not create warehouse.'; this.busy = false; }
    });
  }

  selectWarehouse(row: Warehouse): void { this.selectedWarehouse = row; this.selectedStock = null; this.stocks = []; this.loadStock(); }
  loadStock(): void {
    if (!this.selectedWarehouse) return;
    this.http.get<Stock[]>(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock`).subscribe({
      next: rows => { this.stocks = rows; if (this.selectedStock) this.selectedStock = rows.find(row => row.sellableId === this.selectedStock?.sellableId) ?? this.selectedStock; },
      error: e => this.error = e.error?.detail ?? 'Could not load warehouse stock.'
    });
  }

  manage(row: Stock): void { this.selectedStock = row; this.draft = { sellableId: row.sellableId, quantity: 1, reason: '' }; this.loadMovements(); }
  loadMovements(): void {
    if (!this.selectedWarehouse || !this.selectedStock) return;
    this.http.get<Movement[]>(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${this.selectedStock.sellableId}/movements?limit=100`).subscribe({
      next: rows => this.movements = rows,
      error: e => this.error = e.error?.detail ?? 'Could not load stock movement history.'
    });
  }

  receive(): void {
    if(!this.draft.sellableId){this.error='Choose a product variant before receiving stock.';return;}
    if (!this.selectedWarehouse) return;
    this.apply('POST', this.draft.sellableId.trim(), { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() || null });
  }

  receiveManaged(): void {
    if (!this.selectedStock) return;
    this.apply('POST', this.selectedStock.sellableId, { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() || 'Staff stock receipt' });
  }

  adjust(): void {
    if (!this.selectedStock || !this.draft.reason.trim()) { this.error = 'Enter a reason for the on-hand correction.'; return; }
    this.apply('PUT', this.selectedStock.sellableId, { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() });
  }

  private apply(method: 'POST' | 'PUT', sellableId: string, body: { quantity: number; reason: string | null }): void {
    if (!this.selectedWarehouse || !sellableId) return;
    if (!Number.isFinite(body.quantity) || body.quantity < 0 || method === 'POST' && body.quantity <= 0) { this.error = method === 'POST' ? 'Receipt quantity must be greater than zero.' : 'On-hand quantity must be zero or greater.'; return; }
    if (method === 'PUT' && !window.confirm('Set the absolute on-hand balance to ' + body.quantity + '? Reserved stock will remain committed.')) return;
    this.busy = true;
    const url = `/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${sellableId}` + (method === 'POST' ? '/receipts' : '');
    const request = method === 'POST' ? this.http.post<Stock>(url, body) : this.http.put<Stock>(url, body);
    request.subscribe({
      next: row => { this.busy = false; this.error = ''; this.draft.sellableId = row.sellableId; this.selectedStock = row; this.loadStock(); this.loadMovements(); this.loadLowStock(); },
      error: e => { this.error = e.error?.detail ?? 'Could not update inventory.'; this.busy = false; }
    });
  }
}
