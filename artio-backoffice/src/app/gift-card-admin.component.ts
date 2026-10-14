import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

type GiftCard = { id: string; initialBalance: number; balance: number; currency: string; expiresAt: string | null; active: boolean; createdAt: string };
type GiftCardTransaction = { id: string; orderId: string | null; paymentRefundId: string | null; amount: number; kind: string; createdAt: string };

@Component({
  selector: 'artio-gift-card-admin',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section>
      <div class="panel">
        <div class="panel-heading"><div><p class="eyebrow">STORED VALUE</p><h2>Issue a gift card</h2></div></div>
        <form class="create-form" (ngSubmit)="issue()">
          <label>Initial amount<input type="number" min="0.01" step="0.01" [(ngModel)]="draft.amount" name="amount" required></label>
          <label>Currency<input maxlength="3" minlength="3" [(ngModel)]="draft.currency" name="currency" required></label>
          <label>Expiry date (optional)<input type="date" [(ngModel)]="draft.expiresAt" name="expiresAt"></label>
          <button class="primary" [disabled]="busy">{{busy?'Issuing…':'Issue gift card'}}</button>
        </form>
        <div *ngIf="issuedCode" class="notice" role="status"><strong>Copy this code now. It cannot be retrieved later.</strong><p><code>{{issuedCode}}</code></p><button class="link-button" (click)="copyCode()">Copy code</button><button class="link-button" (click)="issuedCode=null">Dismiss</button></div>
      </div>

      <div class="panel table-panel">
        <div class="panel-heading"><div><p class="eyebrow">CARD REGISTER</p><h2>Gift cards</h2></div><button class="link-button" (click)="load()">Refresh</button></div>
        <table><thead><tr><th>ISSUED</th><th>INITIAL</th><th>BALANCE</th><th>EXPIRY</th><th>STATUS</th><th>ACTIONS</th></tr></thead>
          <tbody><tr *ngFor="let card of cards">
            <td><code>{{card.id}}</code></td><td>{{card.initialBalance}} {{card.currency}}</td><td>{{card.balance}} {{card.currency}}</td>
            <td>{{card.expiresAt ? (card.expiresAt | date:'mediumDate') : 'Never'}}</td><td>{{card.active?'Active':'Disabled'}}</td>
            <td><button class="link-button" (click)="toggle(card)">{{card.active?'Disable':'Enable'}}</button><button class="link-button" (click)="viewTransactions(card)">Ledger</button></td>
          </tr></tbody>
        </table>
        <p *ngIf="!cards.length" class="empty">No gift cards have been issued.</p>
      </div>

      <div *ngIf="selectedCard" class="panel table-panel">
        <div class="panel-heading"><div><p class="eyebrow">IMMUTABLE LEDGER</p><h2>Transactions · {{selectedCard.id}}</h2></div><button class="link-button" (click)="selectedCard=null">Close</button></div>
        <table><thead><tr><th>DATE</th><th>KIND</th><th>AMOUNT</th><th>ORDER / REFUND</th></tr></thead><tbody>
          <tr *ngFor="let entry of transactions"><td>{{entry.createdAt | date:'medium'}}</td><td>{{entry.kind}}</td><td>{{entry.amount}} {{selectedCard.currency}}</td><td>{{entry.orderId||entry.paymentRefundId||'—'}}</td></tr>
        </tbody></table><p *ngIf="!transactions.length" class="empty">No balance movements yet.</p>
      </div>
      <p class="error" *ngIf="error" role="alert">{{error}}</p>
    </section>
  `
})
export class GiftCardAdminComponent {
  private readonly http = inject(HttpClient);
  cards: GiftCard[] = [];
  transactions: GiftCardTransaction[] = [];
  selectedCard: GiftCard | null = null;
  issuedCode: string | null = null;
  busy = false;
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
    const active = !card.active;
    if (!window.confirm(`${active ? 'Enable' : 'Disable'} this card? ${active ? '' : 'Disabling prevents redemption and refunds until it is re-enabled.'}`)) return;
    this.http.put<GiftCard>(`/api/v1/admin/gift-cards/${card.id}/active`, { active }).subscribe({
      next: updated => { this.cards = this.cards.map(item => item.id === updated.id ? updated : item); this.error = ''; },
      error: e => this.error = e.error?.detail ?? 'Could not update gift card status.'
    });
  }

  viewTransactions(card: GiftCard): void {
    this.selectedCard = card;
    this.transactions = [];
    this.http.get<GiftCardTransaction[]>(`/api/v1/admin/gift-cards/${card.id}/transactions`).subscribe({
      next: rows => this.transactions = rows,
      error: e => this.error = e.error?.detail ?? 'Could not load gift card ledger.'
    });
  }
}
