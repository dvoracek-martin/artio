import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { CURRENCY_OPTIONS } from './commerce-options';
import { SearchSelectComponent } from './search-select.component';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';
import { giftCardAdminText } from './gift-card-admin-locale';

type GiftCard = { id: string; initialBalance: number; balance: number; currency: string; expiresAt: string | null; active: boolean; createdAt: string };
type GiftCardTransaction = { id: string; orderId: string | null; paymentRefundId: string | null; amount: number; kind: string; createdAt: string };

@Component({
    selector: 'artio-gift-card-admin',
    imports: [CommonModule, FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section>
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">{{gt('eyebrow')}}</p><h2>{{gt('issueTitle')}}</h2></div></div>
        <form class="create-form" (ngSubmit)="issue()">
          <label>{{gt('initialAmount')}}<input type="number" min="0.01" step="0.01" [(ngModel)]="draft.amount" name="amount" required></label>
          <label>{{gt('currency')}}<artio-search-select [(ngModel)]="draft.currency" name="currency" [label]="gt('currencyLabel')" [options]="currencyOptions"></artio-search-select></label>
          <label>{{gt('expiry')}}<input type="date" [(ngModel)]="draft.expiresAt" name="expiresAt"></label>
          <button class="primary" [disabled]="busy">{{busy?gt('issuing'):gt('issue')}}</button>
        </form>
        @if (issuedCode) {
          <div class="notice" role="status"><strong>{{gt('copyNow')}}</strong><p><code>{{issuedCode}}</code></p><button class="link-button" (click)="copyCode()">{{gt('copyCode')}}</button><button class="link-button" (click)="issuedCode=null">{{gt('dismiss')}}</button></div>
        }
      </div>

      <div class="panel table-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{gt('register')}}</p><h2>{{gt('cards')}}</h2></div><button class="link-button" (click)="load()">{{gt('refresh')}}</button></div>
        @if (loading) { <p class="muted" role="status">{{gt('loadingCards')}}</p> }
        <table><thead><tr><th>{{gt('issued')}}</th><th>{{gt('initial')}}</th><th>{{gt('balance')}}</th><th>{{gt('expiryColumn')}}</th><th>{{gt('status')}}</th><th>{{gt('actions')}}</th></tr></thead>
        <tbody>@for (card of cards; track card) {
          <tr>
            <td><code>{{card.id}}</code></td><td>{{card.initialBalance}} {{card.currency}}</td><td>{{card.balance}} {{card.currency}}</td>
            <td>{{card.expiresAt ? (card.expiresAt | date:'mediumDate') : gt('never')}}</td><td>{{card.active?gt('active'):gt('disabled')}}</td>
            <td><button class="link-button" (click)="toggle(card)">{{card.active?gt('disable'):gt('enable')}}</button><button class="link-button" (click)="viewTransactions(card)">{{gt('ledger')}}</button></td>
          </tr>
        }</tbody>
      </table>
      @if (!loading && !cards.length) {
        <p class="empty">{{gt('noCards')}}</p>
      }
    </div>

    @if (statusTarget) {
      <dialog #statusDialog class="refund-dialog" aria-labelledby="gift-card-status-title" (cancel)="cancelStatusDialog($event)" (close)="statusDialogClosed()">
        <form class="refund-dialog-content" (ngSubmit)="submitStatusChange()">
          <p class="eyebrow">{{t('giftCardStatusEyebrow')}}</p>
          <h2 id="gift-card-status-title">{{statusTarget.active?t('disableGiftCardTitle'):t('enableGiftCardTitle')}}</h2>
          <p class="muted">{{statusTarget.active?t('disableGiftCardDescription'):t('enableGiftCardDescription')}}</p>
          <p class="refund-payment-summary"><strong>{{statusTarget.id}}</strong><span>{{statusTarget.balance}} {{statusTarget.currency}}</span></p>
          @if (statusError) { <p class="error" role="alert">{{statusError}}</p> }
          @if (statusBusy) { <p class="muted" role="status">{{t('giftCardStatusBusy')}}</p> }
          <div class="refund-dialog-actions">
            <button type="button" class="secondary" [disabled]="statusBusy" (click)="closeStatusDialog()">{{t('refundCancel')}}</button>
            <button type="submit" class="primary" [disabled]="statusBusy">{{t('giftCardStatusConfirm')}}</button>
          </div>
        </form>
      </dialog>
    }

    @if (selectedCard) {
      <div class="panel table-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{gt('immutableLedger')}}</p><h2>{{gt('transactions')}} · {{selectedCard.id}}</h2></div><button class="link-button" (click)="selectedCard=null">{{gt('close')}}</button></div>
        @if (transactionsLoading) { <p class="muted" role="status">{{gt('loadingTransactions')}}</p> }
        <table><thead><tr><th>{{gt('date')}}</th><th>{{gt('kind')}}</th><th>{{gt('amount')}}</th><th>{{gt('orderRefund')}}</th></tr></thead><tbody>
        @for (entry of transactions; track entry) {
          <tr><td>{{entry.createdAt | date:'medium'}}</td><td>{{entry.kind}}</td><td>{{entry.amount}} {{selectedCard.currency}}</td><td>{{entry.orderId||entry.paymentRefundId||'—'}}</td></tr>
        }
      </tbody></table>@if (!transactionsLoading && !transactions.length) {
      <p class="empty">{{gt('noMovements')}}</p>
    }
    </div>
    }
    @if (error) {
      <p class="error" role="alert">{{error}}</p>
    }
    </section>
    `
})
export class GiftCardAdminComponent {
  @ViewChild('statusDialog') private statusDialogRef?:ElementRef<HTMLDialogElement>;
  t(key:BackofficeTextKey):string{return backofficeText(currentBackofficeLocale(),key)}
  gt(key:string):string{return giftCardAdminText(currentBackofficeLocale(),key)}
  currencyOptions=CURRENCY_OPTIONS;
  private readonly http = inject(HttpClient);
  cards: GiftCard[] = [];
  loading = false; transactionsLoading = false;
  transactions: GiftCardTransaction[] = [];
  selectedCard: GiftCard | null = null;
  issuedCode: string | null = null;
  busy = false;
  statusTarget:GiftCard|null=null;
  statusBusy=false;
  statusError='';
  error = '';
  draft = { amount: 50, currency: 'CHF', expiresAt: '' };

  constructor() { this.load(); }

  load(): void {
    this.loading=true;
    this.http.get<GiftCard[]>('/api/v1/admin/gift-cards').subscribe({
      next: cards => {this.cards = cards;this.loading=false},
      error: e => {this.loading=false;this.error = e.error?.detail ?? this.gt('loadFailure')}
    });
  }

  issue(): void {
    this.error = '';
    this.busy = true;
    const expiresAt = this.draft.expiresAt
      ? new Date(`${this.draft.expiresAt}T23:59:59.999`).toISOString()
      : null;
    this.http.post<{code: string}>('/api/v1/admin/gift-cards', {
      amount: Number(this.draft.amount), currency: this.draft.currency.trim().toUpperCase(), expiresAt
    }).subscribe({
      next: result => { this.issuedCode = result.code; this.busy = false; this.load(); },
      error: e => { this.error = e.error?.detail ?? this.gt('issueFailure'); this.busy = false; }
    });
  }

  async copyCode(): Promise<void> {
    if (!this.issuedCode) return;
    try { await navigator.clipboard.writeText(this.issuedCode); }
    catch { this.error = this.gt('clipboardFailure'); }
  }

  toggle(card: GiftCard): void {
    this.statusTarget=card;this.statusBusy=false;this.statusError='';setTimeout(()=>{const dialog=this.statusDialogRef?.nativeElement;if(this.statusTarget?.id===card.id&&dialog&&!dialog.open)dialog.showModal()},0);
  }

  cancelStatusDialog(event:Event):void{if(this.statusBusy)event.preventDefault()}
  statusDialogClosed():void{if(this.statusBusy)return;this.statusTarget=null;this.statusError=''}
  closeStatusDialog():void{if(!this.statusBusy)this.statusDialogRef?.nativeElement.close()}
  submitStatusChange():void{const card=this.statusTarget;if(!card||this.statusBusy)return;this.statusBusy=true;this.statusError='';this.http.put<GiftCard>(`/api/v1/admin/gift-cards/${card.id}/active`,{active:!card.active}).subscribe({next:updated=>{this.cards=this.cards.map(item=>item.id===updated.id?updated:item);this.statusBusy=false;this.statusDialogRef?.nativeElement.close();this.error=''},error:e=>{this.statusBusy=false;this.statusError=e.error?.detail??this.t('giftCardStatusFailure')}})}

  viewTransactions(card: GiftCard): void {
    this.selectedCard = card;
    this.transactions = [];
    this.transactionsLoading=true;
    this.http.get<GiftCardTransaction[]>(`/api/v1/admin/gift-cards/${card.id}/transactions`).subscribe({
      next: rows => {this.transactions = rows;this.transactionsLoading=false},
      error: e => {this.transactionsLoading=false;this.error = e.error?.detail ?? this.gt('ledgerFailure')}
    });
  }
}
