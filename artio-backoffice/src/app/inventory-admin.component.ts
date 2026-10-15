import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { SellableSelectComponent } from './sellable-select.component';
import { SearchSelectComponent, SelectOption } from './search-select.component';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';
import { inventoryAdminText } from './inventory-admin-locale';

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
          <div class="panel-heading"><div><p class="eyebrow">{{it('replenishment')}}</p><h2>{{it('lowStock')}}</h2></div><button class="link-button" (click)="loadLowStock()">{{it('refresh')}}</button></div>
          @for (row of lowStock; track row) {
            <div class="row"><span class="row-main"><strong>{{row.sellableId}}</strong><small>{{warehouseName(row.warehouseId)}} · {{row.available}} {{it('available')}} · {{it('threshold')}} {{row.threshold}}</small></span><button class="link-button" (click)="openLowStock(row)">{{it('manage')}}</button></div>
          }
        </div>
      }
      @if (lowStockLoading) { <p class="muted" role="status">{{it('loadingLowStock')}}</p> }
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">{{it('stockLocations')}}</p><h2>{{it('warehouses')}}</h2></div><button class="link-button" (click)="loadWarehouses()">{{it('refresh')}}</button></div>
        <form class="create-form" (ngSubmit)="createWarehouse()">
          <input [(ngModel)]="warehouseDraft.code" name="warehouseCode" maxlength="32" [placeholder]="it('codeExample')" [attr.aria-label]="it('codeExample')" required>
          <input [(ngModel)]="warehouseDraft.name" name="warehouseName" maxlength="200" [placeholder]="it('warehouseName')" [attr.aria-label]="it('warehouseName')" required>
          <input [(ngModel)]="warehouseDraft.location" name="warehouseLocation" maxlength="300" [placeholder]="it('location')" [attr.aria-label]="it('location')" required>
          <button class="primary" [disabled]="busy">{{it('addWarehouse')}}</button>
        </form>
        @if (warehousesLoading) { <p class="muted" role="status">{{it('loadingWarehouses')}}</p> }
        @for (warehouse of warehouses; track warehouse) {
          <div class="row">
            <span class="row-main"><strong>{{warehouse.name}} · {{warehouse.code}}</strong><small>{{warehouse.location}} · {{warehouse.active?it('active'):it('inactive')}}</small></span>
            <button class="link-button" (click)="selectWarehouse(warehouse)">{{selectedWarehouse?.id===warehouse.id?it('selected'):it('viewStock')}}</button>
          </div>
        }
        @if (warehouses.length) {
          <div class="setup-field"><span>{{it('jumpWarehouse')}}</span><artio-search-select [ngModel]="selectedWarehouse?.id || ''" (ngModelChange)="chooseWarehouse($event)" [label]="it('selectWarehouseLabel')" [options]="warehouseOptions" [placeholder]="it('chooseWarehouse')"></artio-search-select></div>
          }@if (!warehousesLoading && !warehouses.length) {
          <p class="empty">{{it('createWarehouseHelp')}}</p>
        }
      </div>

      @if (selectedWarehouse) {
        <div class="panel table-panel">
          <div class="panel-heading"><div><p class="eyebrow">{{selectedWarehouse.code}}</p><h2>{{selectedWarehouse.name}} {{it('warehouseStock')}}</h2></div><button class="link-button" (click)="loadStock()">{{it('refresh')}}</button></div>
          @if (stockLoading) { <p class="muted" role="status">{{it('loadingStock')}}</p> }
          <table><thead><tr><th>{{it('sellableId')}}</th><th>{{it('onHand')}}</th><th>{{it('reserved')}}</th><th>{{it('availableHeader')}}</th><th>{{it('action')}}</th></tr></thead><tbody>
          @for (row of stocks; track row) {
            <tr><td><code>{{row.sellableId}}</code></td><td>{{row.onHand}}</td><td>{{row.reserved}}</td><td>{{row.available}}</td><td><button class="link-button" (click)="manage(row)">{{it('receiveAdjustHistory')}}</button></td></tr>
          }
        </tbody></table>@if (!stockLoading && !stocks.length) {
        <p class="empty">{{it('noStock')}}</p>
      }
      <form class="create-form" (ngSubmit)="receive()"><artio-sellable-select [(ngModel)]="draft.sellableId" name="newSellableId" [label]="it('receiptVariant')"></artio-sellable-select><input [(ngModel)]="draft.quantity" name="newQuantity" type="number" min="0.0001" step="0.0001" [placeholder]="it('receiptQuantity')" [attr.aria-label]="it('receiptQuantity')" required><input [(ngModel)]="draft.reason" name="newReceiptReason" maxlength="500" [placeholder]="it('receiptNote')" [attr.aria-label]="it('receiptNote')"><button class="primary" [disabled]="busy">{{it('receiveStock')}}</button></form>
    </div>
    @if (selectedStock) {
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">{{it('stockControl')}}</p><h2>{{selectedStock.sellableId}}</h2></div><button class="link-button" (click)="selectedStock=null">{{it('close')}}</button></div>
        <p>{{it('current')}}: {{selectedStock.onHand}} {{it('onHand')}} · {{selectedStock.reserved}} {{it('reserved')}} · {{selectedStock.available}} {{it('available')}}</p>
        <div class="create-form"><input [(ngModel)]="draft.quantity" name="managedQuantity" type="number" min="0" step="0.0001" [placeholder]="it('quantity')" [attr.aria-label]="it('quantity')" required><input [(ngModel)]="draft.reason" name="adjustmentReason" maxlength="500" [placeholder]="it('adjustmentReason')" [attr.aria-label]="it('adjustmentReason')"><button class="link-button" (click)="receiveManaged()">{{it('receiveQuantity')}}</button><button class="primary" (click)="adjust()">{{it('setOnHand')}}</button></div>
        <h3>{{it('movements')}}</h3>
        @if (movementsLoading) { <p class="muted" role="status">{{it('loadingMovements')}}</p> }
        @for (item of movements; track item) {
          <div class="row"><span class="row-main"><strong>{{item.type}} · {{it('deltaOnHand')}} {{item.onHandDelta}} · {{it('deltaReserved')}} {{item.reservedDelta}}</strong><small>{{item.occurredAt | date:'medium'}} · {{it('now')}} {{item.onHandAfter}} {{it('onHand')}} / {{item.reservedAfter}} {{it('reserved')}} · {{item.reason||it('systemMovement')}}{{item.actorId?' · '+it('staff')+' '+item.actorId:''}}</small></span></div>
        }
        @if (!movementsLoading && !movements.length) {
          <p class="empty">{{it('noMovements')}}</p>
        }
        <h3>{{it('lowStockAlert')}}</h3>
        <form class="create-form" (ngSubmit)="saveLowStockPolicy()"><input [(ngModel)]="policyDraft.threshold" name="lowStockThreshold" type="number" min="0" step="0.0001" [placeholder]="it('alertThreshold')" [attr.aria-label]="it('alertThreshold')" required><label><input type="checkbox" [(ngModel)]="policyDraft.active" name="lowStockActive"> {{it('enabled')}}</label><button class="primary" [disabled]="busy">{{it('saveThreshold')}}</button></form>
      </div>
    }
    }
    @if (error) {
      <p class="error" role="alert">{{error}}</p>
    }
    @if (adjustmentConfirmation) {
      <dialog #adjustmentDialog class="refund-dialog" aria-labelledby="inventory-adjust-title" (cancel)="cancelAdjustmentDialog($event)" (close)="adjustmentDialogClosed()">
        <form class="refund-dialog-content" (ngSubmit)="confirmAdjustment()">
          <p class="eyebrow">{{t('inventoryAdjustEyebrow')}}</p>
          <h2 id="inventory-adjust-title">{{t('inventoryAdjustTitle')}}</h2>
          <p class="muted">{{t('inventoryAdjustDescription')}}</p>
          <p class="refund-payment-summary"><strong>{{adjustmentConfirmation.body.quantity}}</strong><span>{{adjustmentConfirmation.sellableId}}</span></p>
          @if (adjustmentDialogError) { <p class="error" role="alert">{{adjustmentDialogError}}</p> }
          @if (adjustmentDialogBusy) { <p class="muted" role="status">{{t('inventoryAdjustBusy')}}</p> }
          <div class="refund-dialog-actions">
            <button type="button" class="secondary" [disabled]="adjustmentDialogBusy" (click)="closeAdjustmentDialog()">{{t('refundCancel')}}</button>
            <button type="submit" class="primary" [disabled]="adjustmentDialogBusy">{{t('inventoryAdjustConfirm')}}</button>
          </div>
        </form>
      </dialog>
    }
    </section>
    `
})
export class InventoryAdminComponent {
  @ViewChild('adjustmentDialog') private adjustmentDialogRef?:ElementRef<HTMLDialogElement>;
  t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
  it(key:string):string{return inventoryAdminText(currentBackofficeLocale(),key)}
  private readonly http = inject(HttpClient);
  warehouses: Warehouse[] = [];
  warehousesLoading = false; stockLoading = false; movementsLoading = false; lowStockLoading = false;
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
  adjustmentConfirmation:{warehouseId:string;sellableId:string;body:{quantity:number;reason:string|null}}|null=null;
  adjustmentDialogBusy=false;
  adjustmentDialogError='';

  get warehouseOptions():SelectOption[]{return this.warehouses.map(x=>({value:x.id,label:x.name,detail:x.code+' · '+x.location,disabled:!x.active}));}
  chooseWarehouse(id:string):void{const row=this.warehouses.find(x=>x.id===id);if(row)this.selectWarehouse(row);}
  constructor() { this.loadWarehouses(); this.loadLowStock(); }

  loadLowStock(): void { this.lowStockLoading=true;this.http.get<LowStock[]>('/api/v1/inventory/low-stock').subscribe({next:rows=>{this.lowStock=rows;this.lowStockLoading=false},error:e=>{this.lowStockLoading=false;this.error=e.error?.detail??this.it('lowStockLoadFailure')}}); }
  warehouseName(id:string):string{return this.warehouses.find(row=>row.id===id)?.name??id;}
  openLowStock(row:LowStock):void{const warehouse=this.warehouses.find(item=>item.id===row.warehouseId);if(!warehouse)return;this.selectedWarehouse=warehouse;this.loadStock();this.http.get<Array<{sellableId:string;threshold:number;active:boolean}>>(`/api/v1/warehouses/${warehouse.id}/stock-policies`).subscribe({next:policies=>{const policy=policies.find(item=>item.sellableId===row.sellableId);this.policyDraft=policy?{threshold:policy.threshold,active:policy.active}:{threshold:row.threshold,active:true};this.selectedStock={warehouseId:row.warehouseId,sellableId:row.sellableId,onHand:0,reserved:0,available:row.available};this.loadMovements();},error:e=>this.error=e.error?.detail??this.it('policiesLoadFailure')});}
  saveLowStockPolicy():void{if(!this.selectedWarehouse||!this.selectedStock)return;this.busy=true;this.http.put(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${this.selectedStock.sellableId}/low-stock-policy`,this.policyDraft).subscribe({next:()=>{this.busy=false;this.error='';this.loadLowStock();},error:e=>{this.busy=false;this.error=e.error?.detail??this.it('policySaveFailure');}});}

  loadWarehouses(): void {
    this.warehousesLoading = true;
    this.http.get<Warehouse[]>('/api/v1/warehouses').subscribe({
      next: rows => { this.warehouses = rows; this.warehousesLoading=false; if (this.selectedWarehouse) this.selectedWarehouse = rows.find(row => row.id === this.selectedWarehouse?.id) ?? null; },
      error: e => {this.warehousesLoading=false;this.error = e.error?.detail ?? this.it('warehousesLoadFailure')}
    });
  }

  createWarehouse(): void {
    this.busy = true;
    this.http.post<Warehouse>('/api/v1/warehouses', { ...this.warehouseDraft, code: this.warehouseDraft.code.trim().toUpperCase() }).subscribe({
      next: row => { this.warehouseDraft = { code: '', name: '', location: '' }; this.busy = false; this.loadWarehouses(); this.selectWarehouse(row); },
      error: e => { this.error = e.error?.detail ?? this.it('warehouseCreateFailure'); this.busy = false; }
    });
  }

  selectWarehouse(row: Warehouse): void { this.selectedWarehouse = row; this.selectedStock = null; this.stocks = []; this.loadStock(); }
  loadStock(): void {
    if (!this.selectedWarehouse) return;
    this.stockLoading=true;
    this.http.get<Stock[]>(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock`).subscribe({
      next: rows => { this.stocks = rows; this.stockLoading=false; if (this.selectedStock) this.selectedStock = rows.find(row => row.sellableId === this.selectedStock?.sellableId) ?? this.selectedStock; },
      error: e => {this.stockLoading=false;this.error = e.error?.detail ?? this.it('stockLoadFailure')}
    });
  }

  manage(row: Stock): void { this.selectedStock = row; this.draft = { sellableId: row.sellableId, quantity: 1, reason: '' }; this.loadMovements(); }
  loadMovements(): void {
    if (!this.selectedWarehouse || !this.selectedStock) return;
    this.movementsLoading=true;
    this.http.get<Movement[]>(`/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${this.selectedStock.sellableId}/movements?limit=100`).subscribe({
      next: rows => {this.movements = rows;this.movementsLoading=false},
      error: e => {this.movementsLoading=false;this.error = e.error?.detail ?? this.it('movementsLoadFailure')}
    });
  }

  receive(): void {
    if(!this.draft.sellableId){this.error=this.it('chooseVariant');return;}
    if (!this.selectedWarehouse) return;
    this.apply('POST', this.draft.sellableId.trim(), { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() || null });
  }

  receiveManaged(): void {
    if (!this.selectedStock) return;
    this.apply('POST', this.selectedStock.sellableId, { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() || this.it('staffReceipt') });
  }

  adjust(): void {
    if (!this.selectedStock || !this.draft.reason.trim()) { this.error = this.it('reasonRequired'); return; }
    this.apply('PUT', this.selectedStock.sellableId, { quantity: Number(this.draft.quantity), reason: this.draft.reason.trim() });
  }

  private apply(method: 'POST' | 'PUT', sellableId: string, body: { quantity: number; reason: string | null }): void {
    if (!this.selectedWarehouse || !sellableId) return;
    if (!Number.isFinite(body.quantity) || body.quantity < 0 || method === 'POST' && body.quantity <= 0) { this.error = method === 'POST' ? this.it('receiptPositive') : this.it('quantityNonnegative'); return; }
    if (method === 'PUT') { this.adjustmentConfirmation={warehouseId:this.selectedWarehouse.id,sellableId,body};this.adjustmentDialogBusy=false;this.adjustmentDialogError='';setTimeout(()=>{if(this.adjustmentConfirmation&&!this.adjustmentDialogRef?.nativeElement.open)this.adjustmentDialogRef?.nativeElement.showModal()},0);return; }
    this.performApply(method,sellableId,body);
  }

  cancelAdjustmentDialog(event:Event):void{if(this.adjustmentDialogBusy)event.preventDefault()}
  adjustmentDialogClosed():void{if(this.adjustmentDialogBusy)return;this.adjustmentConfirmation=null;this.adjustmentDialogError=''}
  closeAdjustmentDialog():void{if(!this.adjustmentDialogBusy)this.adjustmentDialogRef?.nativeElement.close()}
  confirmAdjustment():void{if(!this.adjustmentConfirmation||this.adjustmentDialogBusy)return;const pending=this.adjustmentConfirmation;this.adjustmentDialogBusy=true;this.adjustmentDialogError='';this.http.put<Stock>(`/api/v1/warehouses/${pending.warehouseId}/stock/${pending.sellableId}`,pending.body).subscribe({next:row=>{this.adjustmentDialogBusy=false;this.adjustmentDialogRef?.nativeElement.close();this.error='';this.draft.sellableId=row.sellableId;this.selectedStock=row;this.loadStock();this.loadMovements();this.loadLowStock()},error:e=>{this.adjustmentDialogBusy=false;this.adjustmentDialogError=e.error?.detail??this.t('inventoryAdjustFailure')}})}

  private performApply(method: 'POST' | 'PUT', sellableId: string, body: { quantity: number; reason: string | null }): void {
    if (!this.selectedWarehouse || !sellableId) return;
    this.busy = true;
    const url = `/api/v1/warehouses/${this.selectedWarehouse.id}/stock/${sellableId}` + (method === 'POST' ? '/receipts' : '');
    const request = method === 'POST' ? this.http.post<Stock>(url, body) : this.http.put<Stock>(url, body);
    request.subscribe({
      next: row => { this.busy = false; this.error = ''; this.draft.sellableId = row.sellableId; this.selectedStock = row; this.loadStock(); this.loadMovements(); this.loadLowStock(); },
      error: e => { this.error = e.error?.detail ?? this.it('stockUpdateFailure'); this.busy = false; }
    });
  }
}
