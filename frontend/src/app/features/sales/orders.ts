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

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [FormsModule, RouterLink, DatePipe, MoneyPipe, ShortIdPipe, ModalComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div><h1>Orders</h1><div class="sub">Sales history and order lifecycle controls for the active branch.</div></div>
        <button class="btn btn-primary" routerLink="/pos">Open POS</button>
      </div>

      <div class="split">
        <div class="card">
          @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
          @else {
            <div class="table-wrap">
              <table class="data">
                <thead><tr><th>Order</th><th>Created</th><th>Type</th><th>Status</th><th>Total</th><th>Paid</th></tr></thead>
                <tbody>
                  @for (o of orders(); track o.id) {
                    <tr (click)="select(o)" [class.row-selected]="selected()?.id === o.id">
                      <td><b>{{ o.id | shortId }}</b><div class="soft">{{ o.lines.length }} line(s)</div></td>
                      <td>{{ o.createdAt | date:'short' }}</td>
                      <td>{{ o.orderType }}</td>
                      <td><span class="badge" [class]="statusClass(o.status)">{{ o.status }}</span></td>
                      <td>{{ o.grandTotal | money:o.currency }}</td>
                      <td>{{ o.amountPaid | money:o.currency }}</td>
                    </tr>
                  } @empty { <tr><td colspan="6"><div class="empty"><div class="big">☕</div>No orders yet</div></td></tr> }
                </tbody>
              </table>
            </div>
          }
        </div>

        <div class="card detail">
          @if (selected(); as o) {
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

            @if (auth.can('sales:write')) {
              <div class="action-bar">
                @if (o.status === 'OPEN') {
                  <button class="btn btn-outline" (click)="openPayment(o)">Add payment</button>
                  <button class="btn btn-outline" (click)="cancel(o)">Cancel</button>
                }
                @if (o.status === 'PAID') { <button class="btn btn-primary" (click)="complete(o)">Complete</button> }
                @if (auth.can('sales:refund') && (o.status === 'PAID' || o.status === 'COMPLETED')) { <button class="btn btn-outline" (click)="refund(o)">Refund</button> }
                @if (auth.can('sales:refund') && o.status === 'PAID') { <button class="btn btn-outline" (click)="voidOrder(o)">Void</button> }
              </div>
            }
          } @else {
            <div class="empty"><div class="big">🧾</div>Select an order</div>
          }
        </div>
      </div>
    </div>

    @if (paying(); as o) {
      <app-modal [title]="'Add payment to ' + (o.id | shortId)" (close)="paying.set(null)">
        <div class="field">
          <label>Method</label>
          <select class="input" [(ngModel)]="payment.method">
            <option value="CASH">Cash</option>
            <option value="CARD">Card</option>
            <option value="EWALLET">E-wallet</option>
          </select>
        </div>
        <div class="field"><label>Amount</label><input class="input" type="number" min="0" [(ngModel)]="payment.amount" /></div>
        <div footer>
          <button class="btn btn-outline" (click)="paying.set(null)">Cancel</button>
          <button class="btn btn-primary" (click)="addPayment()" [disabled]="saving()">@if (saving()) { <span class="spinner"></span> } Add payment</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .split { display: grid; grid-template-columns: minmax(0, 1fr) 430px; gap: 1rem; align-items: start; }
    .row-selected { background: #eef6ff; }
    .detail-title { display: flex; justify-content: space-between; gap: 1rem; align-items: start; margin-bottom: 1rem; }
    .detail h2 { margin: 0; font-size: 1.25rem; }
    .totals { display: grid; gap: .45rem; margin-bottom: 1rem; }
    .totals > div, .payment-row, .order-line { display: flex; justify-content: space-between; gap: 1rem; }
    .grand { border-top: 1px solid var(--line); padding-top: .55rem; font-size: 1.05rem; }
    .section-title { margin: 1rem 0 .5rem; font-weight: 800; color: var(--ink); }
    .line-list { display: grid; gap: .65rem; }
    .order-line { align-items: start; padding: .65rem 0; border-bottom: 1px solid var(--line); }
    .action-bar { display: flex; flex-wrap: wrap; gap: .5rem; margin-top: 1rem; }
    @media (max-width: 980px) { .split { grid-template-columns: 1fr; } }
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
  refresh(updated: OrderResponse, message: string) {
    this.toast.success(message);
    this.selected.set(updated);
    this.load();
  }

  openPayment(o: OrderResponse) { this.payment = { method: 'CASH', amount: (Number(o.grandTotal) - Number(o.amountPaid)).toString() }; this.paying.set(o); }
  addPayment() {
    const o = this.paying();
    if (!o) return;
    this.saving.set(true);
    this.api.addPayment(o.id, this.payment).subscribe({
      next: (updated) => { this.saving.set(false); this.paying.set(null); this.refresh(updated, 'Payment added'); },
      error: () => this.saving.set(false),
    });
  }

  complete(o: OrderResponse) { this.api.complete(o.id).subscribe({ next: (updated) => this.refresh(updated, 'Order completed') }); }
  async cancel(o: OrderResponse) {
    const ok = await this.confirm.ask({ title: `Cancel ${o.id.slice(0, 8)}?`, danger: true, confirmText: 'Cancel order' });
    if (ok) this.api.cancel(o.id).subscribe({ next: (updated) => this.refresh(updated, 'Order cancelled') });
  }
  voidOrder(o: OrderResponse) { this.api.void(o.id, { method: 'CASH' }).subscribe({ next: (updated) => this.refresh(updated, 'Order voided') }); }
  refund(o: OrderResponse) { this.api.refund(o.id, { method: 'CASH' }).subscribe({ next: (updated) => this.refresh(updated, 'Order refunded') }); }
}
