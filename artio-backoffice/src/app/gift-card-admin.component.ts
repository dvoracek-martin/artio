import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, ChangeDetectionStrategy, ElementRef, ViewChild } from '@angular/core';
import { CURRENCY_OPTIONS } from './commerce-options';
import { SearchSelectComponent } from './search-select.component';
import { FormsModule } from '@angular/forms';
import { backofficeText, currentBackofficeLocale, BackofficeTextKey } from './backoffice-locale';

type GiftCard = { id: string; initialBalance: number; balance: number; currency: string; expiresAt: string | null; active: boolean; createdAt: string };
type GiftCardTransaction = { id: string; orderId: string | null; paymentRefundId: string | null; amount: number; kind: string; createdAt: string };

@Component({
    selector: 'artio-gift-card-admin',
    imports: [CommonModule, FormsModule, SearchSelectComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
    <section>
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">STORED VALUE</p><h2>Issue a gift card</h2></div></div>
        <form class="create-form" (ngSubmit)="issue()">
          <label>Initial amount<input type="number" min="0.01" step="0.01" [(ngModel)]="draft.amount" name="amount" required></label>
          <label>Currency<artio-search-select [(ngModel)]="draft.currency" name="currency" label="Gift card currency" [options]="currencyOptions"></artio-search-select></label>
          <label>Expiry date (optional)<input type="date" [(ngModel)]="draft.expiresAt" name="expiresAt"></label>
          <button class="primary" [disabled]="busy">{{busy?'Issuing…':'Issue gift card'}}</button>
        </form>
        @if (issuedCode) {
          <div class="notice" role="status"><strong>Copy this code now. It cannot be retrieved later.</strong><p><code>{{issuedCode}}</code></p><button class="link-button" (click)="copyCode()">Copy code</button><button class="link-button" (click)="issuedCode=null">Dismiss</button></div>
        }
      </div>

      <div class="panel table-panel">
        <div class="panel-heading"><div><p class="eyebrow">CARD REGISTER</p><h2>Gift cards</h2></div><button class="link-button" (click)="load()">Refresh</button></div>
        <table><thead><tr><th>ISSUED</th><th>INITIAL</th><th>BALANCE</th><th>EXPIRY</th><th>STATUS</th><th>ACTIONS</th></tr></thead>
        <tbody>@for (card of cards; track card) {
          <tr>
            <td><code>{{card.id}}</code></td><td>{{card.initialBalance}} {{card.currency}}</td><td>{{card.balance}} {{card.currency}}</td>
            <td>{{card.expiresAt ? (card.expiresAt | date:'mediumDate') : 'Never'}}</td><td>{{card.active?'Active':'Disabled'}}</td>
            <td><button class="link-button" (click)="toggle(card)">{{card.active?'Disable':'Enable'}}</button><button class="link-button" (click)="viewTransactions(card)">Ledger</button></td>
          </tr>
        }</tbody>
      </table>
      @if (!cards.length) {
        <p class="empty">No gift cards have been issued.</p>
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
        <div class="panel-heading"><div><p class="eyebrow">IMMUTABLE LEDGER</p><h2>Transactions · {{selectedCard.id}}</h2></div><button class="link-button" (click)="selectedCard=null">Close</button></div>
        <table><thead><tr><th>DATE</th><th>KIND</th><th>AMOUNT</th><th>ORDER / REFUND</th></tr></thead><tbody>
        @for (entry of transactions; track entry) {
          <tr><td>{{entry.createdAt | date:'medium'}}</td><td>{{entry.kind}}</td><td>{{entry.amount}} {{selectedCard.currency}}</td><td>{{entry.orderId||entry.paymentRefundId||'—'}}</td></tr>
        }
      </tbody></table>@if (!transactions.length) {
      <p class="empty">No balance movements yet.</p>
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
  currencyOptions=CURRENCY_OPTIONS;
  private readonly http = inject(HttpClient);
  cards: GiftCard[] = [];
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
    this.http.get<GiftCard[]>('/api/v1/admin/gift-cards').subscribe({
      next: cards => this.cards = cards,
      error: e => this.error = e.error?.detail ?? 'Could not load gift cards.'
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
      error: e => { this.error = e.error?.detail ?? 'Could not issue gift card.'; this.busy = false; }
    });
  }

  async copyCode(): Promise<void> {
    if (!this.issuedCode) return;
    try { await navigator.clipboard.writeText(this.issuedCode); }
    catch { this.error = 'Clipboard access was denied. Select and copy the displayed code.'; }
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
    this.http.get<GiftCardTransaction[]>(`/api/v1/admin/gift-cards/${card.id}/transactions`).subscribe({
      next: rows => this.transactions = rows,
      error: e => this.error = e.error?.detail ?? 'Could not load gift card ledger.'
    });
  }
}
