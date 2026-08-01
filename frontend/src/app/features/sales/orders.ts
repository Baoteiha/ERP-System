import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SalesService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { ConfirmService } from '../../core/confirm.service';
import { OrderResponse, PaymentMethod } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, MoneyPipe, ShortIdPipe, ModalComponent, IconComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Orders</h1><div class="sub">Sales history and order lifecycle controls for the active branch.</div></div>
        <button class="btn btn-primary" routerLink="/pos"><app-icon name="basket" [size]="16" /> Open POS</button>
      </div>

      <div class="split">
        <div class="card">
          @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="table-wrap">
              <table class="data">
                <caption class="sr-only">Orders with creation time, type, status, total and amount paid</caption>
                <thead><tr><th scope="col">Order</th><th scope="col">Created</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col">Total</th><th scope="col">Paid</th></tr></thead>
                <tbody>
                  @for (o of orders(); track o.id) {
                    <tr (click)="select(o)" [class.row-selected]="selected()?.id === o.id">
                      <td><button type="button" class="row-open" (click)="select(o)"><b>{{ o.id | shortId }}</b></button><div class="soft">{{ o.lines.length }} line(s)</div></td>
                      <td>{{ o.createdAt | date:'short' }}</td>
                      <td>{{ o.orderType }}</td>
                      <td><span class="badge" [class]="statusClass(o.status)">{{ o.status }}</span></td>
                      <td>{{ o.grandTotal | money:o.currency }}</td>
                      <td>{{ o.amountPaid | money:o.currency }}</td>
                    </tr>
                  } @empty { <tr><td colspan="6"><div class="empty"><div class="big"><app-icon name="receipt" [size]="30" /></div>No orders yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card detail">
          @if (selected(); as o) {
            <div class="thermal thermal-edge thermal-edge-top o-slip">
            @if (stampColor(o.status); as c) { <span class="stamp stamp-in o-stamp" [style.color]="c" aria-hidden="true">{{ o.status }}</span> }
            <div class="detail-title">
              <div><h2>{{ o.id | shortId }}</h2><div class="sub">{{ o.createdAt | date:'medium' }}</div></div>
              <span class="badge" [class]="statusClass(o.status)">{{ o.status }}</span>
            </div>

            <div class="totals">
              <div><span>Subtotal</span><b>{{ o.subtotal | money:o.currency }}</b></div>
              <div><span>Discount</span><b>{{ o.discountTotal | money:o.currency }}</b></div>
              <div><span>Tax</span><b>{{ o.taxTotal | money:o.currency }}</b></div>
              <div class="grand"><span>Grand total</span><b>{{ o.grandTotal | money:o.currency }}</b></div>
            </div>

            <div class="section-title">Lines</div>
            <div class="line-list">
              @for (line of o.lines; track line.id) {
                <div class="order-line">
                  <div>
                    <b>{{ line.productName }}</b>
                    @for (m of line.modifiers; track m.modifierId) { <div class="soft">+ {{ m.name }} {{ m.priceDelta | money:o.currency }}</div> }
                  </div>
                  <div>x{{ line.quantity }}</div>
                  <b>{{ line.lineTotal | money:o.currency }}</b>
                </div>
              }
            </div>

            <div class="section-title">Payments</div>
            @for (p of o.payments; track p.at + p.method + p.type) {
              <div class="payment-row"><span>{{ p.method }} · {{ p.type }}</span><b>{{ p.amount | money:o.currency }}</b></div>
            } @empty { <div class="soft">No payments yet</div> }
            </div>

            @if (auth.can('sales:write')) {
              <div class="action-bar">
                @if (o.status === 'OPEN') {
                  <button class="btn btn-outline" (click)="openPayment(o)">Add payment</button>
                  <button class="btn btn-outline" (click)="cancel(o)">Cancel</button>
                }
                @if (o.status === 'PAID') { <button class="btn btn-primary" (click)="complete(o)" [disabled]="saving()">Complete</button> }
                @if (auth.can('sales:refund') && (o.status === 'PAID' || o.status === 'COMPLETED')) { <button class="btn btn-outline" (click)="refund(o)" [disabled]="saving()">Refund</button> }
                @if (auth.can('sales:refund') && o.status === 'PAID') { <button class="btn btn-outline" (click)="voidOrder(o)" [disabled]="saving()">Void</button> }
              </div>
            }
          } @else {
            <div class="empty"><div class="big"><app-icon name="receipt" [size]="30" /></div>Select an order</div>
          }
        </div>
      </div>
    </div>

    @if (paying(); as o) {
      <app-modal [title]="'Add payment to ' + (o.id | shortId)" (close)="paying.set(null)">
        <div class="field">
          <label for="ord-method">Method</label>
          <select id="ord-method" class="select" [(ngModel)]="payment.method">
            <option value="CASH">Cash</option>
            <option value="CARD">Card</option>
            <option value="EWALLET">E-wallet</option>
          </select>
        </div>
        <div class="field"><label for="ord-amount">Amount</label><input id="ord-amount" class="input" type="number" min="0" [(ngModel)]="payment.amount" /></div>
        <div footer>
          <button class="btn btn-outline" (click)="paying.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="addPayment()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Add payment</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(560px, 1fr) minmax(420px, 520px); gap: 1.15rem; align-items: start; }
    table.data tbody tr { cursor: pointer; }
    .detail .empty { min-height: 320px; }
    .row-selected, .row-selected:hover { background: var(--azure-soft); box-shadow: inset 3px 0 0 var(--azure); }
    .detail { padding: 1.3rem; max-height: calc(100vh - 170px); overflow: auto; position: sticky; top: 0; }
    .detail-title { display: flex; justify-content: space-between; gap: 1rem; align-items: start; margin-bottom: 1.1rem; }
    .detail h2 { margin: 0; font-size: 1.3rem; font-family: var(--font-mono); letter-spacing: -.03em; }
    .totals { display: grid; gap: .5rem; margin-bottom: 1rem; font-size: 14px; }
    .totals > div, .payment-row { display: flex; justify-content: space-between; gap: 1rem; }
    .totals span, .payment-row span { color: var(--text-soft); }
    .grand { border-top: 1px solid var(--hairline); padding-top: .6rem; font-size: 1.1rem; }
    .grand span { color: var(--text); font-weight: 600; }
    .grand b { color: var(--brand); }
    .section-title { margin: 1.2rem 0 .5rem; font-weight: 700; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .line-list { display: grid; gap: .65rem; }
    .order-line { display: grid; grid-template-columns: minmax(0, 1fr) 44px minmax(96px, auto); gap: .85rem; align-items: start; padding: .7rem 0; border-bottom: 1px solid var(--hairline); }
    .order-line > b, .payment-row > b, .totals b { text-align: right; white-space: nowrap; font-family: var(--font-mono); font-variant-numeric: tabular-nums; letter-spacing: -.02em; }
    .action-bar { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
    /* thermal slip accents */
    .o-slip { margin: 10px 6px 14px; padding: 1rem; box-shadow: 0 2px 6px rgba(40,24,10,.18); }
    .o-slip .detail-title { flex-direction: column; align-items: center; text-align: center; gap: .4rem; margin-bottom: 1rem; }
    .o-slip .sub { font-family: var(--font-mono); font-size: 12.5px; color: var(--paper-ink-soft); }
    .o-stamp { position: absolute; top: 10px; right: 12px; }
    .o-slip .totals span, .o-slip .payment-row span, .o-slip .section-title { color: var(--paper-ink-soft); }
    .o-slip .order-line { border-bottom: 1px dashed var(--paper-line); }
    .o-slip .grand { border-top: 3px double var(--paper-line); }
    .o-slip .grand span { color: var(--paper-ink); }
    .o-slip .grand b { color: var(--paper-accent); }
    @media (max-width: 1120px) { .split { grid-template-columns: 1fr; } .detail { max-height: none; } }
  `],
})
export class OrdersComponent {
  auth = inject(AuthService);
  private api = inject(SalesService);
  private toast = inject(ToastService);
  private confirm = inject(ConfirmService);

  orders = signal<OrderResponse[]>([]);
  selected = signal<OrderResponse | null>(null);
  loading = signal(true);
  saving = signal(false);
  paying = signal<OrderResponse | null>(null);
  payment: { method: PaymentMethod; amount: string } = { method: 'CASH', amount: '' };

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.listOrders().subscribe({
      next: (page) => { this.orders.set(page.content); this.loading.set(false); if (!this.selected() && page.content[0]) this.selected.set(page.content[0]); },
      error: () => this.loading.set(false),
    });
  }

  select(o: OrderResponse) { this.selected.set(o); }
  statusClass(status: string) {
    if (status === 'COMPLETED' || status === 'PAID') return 'badge-green';
    if (status === 'CANCELLED' || status === 'VOID' || status === 'REFUNDED') return 'badge-red';
    return 'badge-blue';
  }
  /* rubber-stamp ink for terminal states only; null suppresses the stamp */
  stampColor(status: string): string | null {
    if (status === 'PAID' || status === 'COMPLETED') return '#2e6b34';
    if (status === 'REFUNDED') return '#8a6116';
    if (status === 'VOID' || status === 'CANCELLED') return '#963120';
    return null;
  }
  refresh(updated: OrderResponse, message: string) {
    this.toast.success(message);
    this.selected.set(updated);
    this.load();
  }

  openPayment(o: OrderResponse) { this.payment = { method: 'CASH', amount: (Number(o.grandTotal) - Number(o.amountPaid)).toString() }; this.paying.set(o); }
  addPayment() {
    const o = this.paying();
    if (!o) return;
    if (!(Number(this.payment.amount) > 0)) { this.toast.error('Enter an amount greater than zero.'); return; }
    this.saving.set(true);
    this.api.addPayment(o.id, this.payment).subscribe({
      next: (updated) => { this.saving.set(false); this.paying.set(null); this.refresh(updated, 'Payment added'); },
      error: () => this.saving.set(false),
    });
  }

  complete(o: OrderResponse) {
    this.saving.set(true);
    this.api.complete(o.id).subscribe({
      next: (updated) => { this.saving.set(false); this.refresh(updated, 'Order completed'); },
      error: () => this.saving.set(false),
    });
  }
  async cancel(o: OrderResponse) {
    const ok = await this.confirm.ask({ title: `Cancel ${o.id.slice(0, 8)}?`, danger: true, confirmText: 'Cancel order' });
    if (ok) this.api.cancel(o.id).subscribe({ next: (updated) => this.refresh(updated, 'Order cancelled') });
  }
  async voidOrder(o: OrderResponse) {
    const ok = await this.confirm.ask({ title: 'Void this order?', message: `Voids ${o.id.slice(0, 8)} and reverses its payment.`, danger: true, confirmText: 'Void order' });
    if (!ok) return;
    this.saving.set(true);
    this.api.void(o.id, { method: 'CASH' }).subscribe({
      next: (updated) => { this.saving.set(false); this.refresh(updated, 'Order voided'); },
      error: () => this.saving.set(false),
    });
  }
  async refund(o: OrderResponse) {
    const ok = await this.confirm.ask({ title: 'Refund this order?', message: `Refunds ${o.id.slice(0, 8)} in full.`, danger: true, confirmText: 'Refund' });
    if (!ok) return;
    this.saving.set(true);
    this.api.refund(o.id, { method: 'CASH' }).subscribe({
      next: (updated) => { this.saving.set(false); this.refresh(updated, 'Order refunded'); },
      error: () => this.saving.set(false),
    });
  }
}
