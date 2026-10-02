import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { forkJoin } from 'rxjs';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CatalogService, InventoryService, ReportingService, SalesService } from '../../core/api.services';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { OrderResponse, PeriodSummary, StockItemResponse } from '../../core/models';
import { IconComponent } from '../../shared/icon';
import { KpiTileComponent } from '../../shared/kpi-tile';
import { reportPeriod, zeroFillDays } from '../reports/reports';

type LoadState = 'loading' | 'ready' | 'error';

/** Change of `cur` against `prev` as a fraction; null when there is no previous value to compare with. */
export function pct(cur: number, prev: number): number | null {
  return prev === 0 ? null : (cur - prev) / prev;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MoneyPipe, ShortIdPipe, IconComponent, KpiTileComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Dashboard</h1>
          <div class="sub">Here's the state of your active branch.</div>
        </div>
      </div>

      <div class="kpis">
        @if (auth.can('report:read')) {
          <app-kpi-tile label="Net sales today" icon="banknote" [value]="salesReady() ? (salesSummary()!.netSales | money) : '—'" [delta]="deltas().net" [trend]="trend()" />
          <app-kpi-tile label="Orders today" icon="receipt" [value]="salesReady() ? '' + salesSummary()!.orderCount : '—'" [delta]="deltas().orders" [trend]="trend()" />
          <app-kpi-tile label="Average order" icon="trending-up" [value]="salesReady() ? (average(salesSummary()) === null ? '—' : (average(salesSummary())! | money)) : '—'" [delta]="deltas().avg" [trend]="trend()" />
          <app-kpi-tile label="Gross margin" icon="grid" [value]="salesReady() ? (salesSummary()!.grossMargin | money) : '—'" [delta]="deltas().margin" [trend]="trend()" />
        } @else {
          <app-kpi-tile label="Recent orders" icon="receipt" [value]="ordersState() === 'ready' ? '' + orders().length : '—'" [delta]="null" [trend]="[]" />
          <app-kpi-tile label="Active products" icon="package" [value]="productsState() === 'ready' ? '' + productCount() : '—'" [delta]="null" [trend]="[]" />
          <app-kpi-tile label="Low-stock items" icon="alert" [value]="stockState() === 'ready' ? '' + lowStock().length : '—'" [delta]="null" [trend]="[]" />
        }
      </div>

      <section class="towers-slot"></section>

      <div class="dash-grid">
        @if (auth.can('sales:read')) {
          <div class="card">
            <div class="card-head"><h2>Recent orders</h2><a class="btn btn-sm btn-outline" routerLink="/orders">View all</a></div>
            @if (ordersState() === 'loading') {
              <div class="loading-block"><span class="spinner"></span> Loading…</div>
            } @else if (ordersState() === 'error') {
              <div class="load-error" role="alert">
                Couldn't load recent orders.
                <button type="button" class="btn btn-sm btn-outline" (click)="loadOrders()">Retry</button>
              </div>
            } @else {
              <div class="table-wrap">
                <table class="data">
                  <caption class="sr-only">Recent orders with type, status and total</caption>
                  <thead><tr><th scope="col">Order</th><th scope="col">Type</th><th scope="col">Status</th><th scope="col" class="right">Total</th></tr></thead>
                  <tbody>
                    @for (o of orders().slice(0, 8); track o.id) {
                      <tr>
                        <td class="mono">#{{ o.id | shortId }}</td>
                        <td>{{ o.orderType }}</td>
                        <td><span class="badge dot" [class]="statusClass(o.status)">{{ o.status }}</span></td>
                        <td class="num">{{ o.grandTotal | money }}</td>
                      </tr>
                    } @empty {
                      <tr><td colspan="4">
                        <div class="empty empty-sm">
                          <div class="big"><app-icon name="receipt" [size]="30" /></div>
                          No orders yet — they'll show up here as sales come in.
                        </div>
                      </td></tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        }

        @if (auth.can('inventory:read')) {
          <div class="card">
            <div class="card-head"><h2>Low stock alerts</h2><a class="btn btn-sm btn-outline" routerLink="/inventory/stock">Manage</a></div>
            <div class="card-pad">
              @if (stockState() === 'loading') {
                <div class="loading-block"><span class="spinner"></span> Checking stock…</div>
              } @else if (stockState() === 'error') {
                <div class="load-error" role="alert">
                  Couldn't load stock levels.
                  <button type="button" class="btn btn-sm btn-outline" (click)="loadStock()">Retry</button>
                </div>
              } @else {
                @for (s of lowStock().slice(0, 8); track s.ingredientId) {
                  <div class="low-row">
                    <span class="mono">{{ s.ingredientId | shortId }}</span>
                    <span class="spacer"></span>
                    <span class="num soft">{{ s.quantityOnHand }} on hand</span>
                    <span class="badge dot badge-red"><app-icon name="boxes" [size]="12" aria-hidden="true" /> reorder {{ s.reorderLevel }}</span>
                  </div>
                } @empty {
                  <div class="empty empty-sm">
                    <div class="big all-good"><app-icon name="check-circle" [size]="30" /></div>
                    All stock above reorder level
                  </div>
                }
              }
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1rem; margin-bottom: 1.4rem; }
    .dash-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 1.2rem; align-items: start; }
    .low-row { display: flex; align-items: center; gap: .7rem; padding: .55rem 0; border-bottom: 1px solid var(--border); font-size: 13px; }
    .low-row:last-child { border-bottom: none; }
    .empty-sm { padding: 1.6rem 1rem; }
    .empty-sm .big.all-good { color: var(--green); }
    .towers-slot:empty { display: none; }
    .load-error { display: flex; align-items: center; justify-content: center; gap: .8rem;
      padding: 2rem 1rem; color: var(--text-soft); font-size: 14px; }
    @media (max-width: 980px) { .dash-grid { grid-template-columns: 1fr; } }
  `],
})
export class DashboardComponent {
  auth = inject(AuthService);
  private sales = inject(SalesService);
  private inventory = inject(InventoryService);
  private catalog = inject(CatalogService);
  private reporting = inject(ReportingService);

  orders = signal<OrderResponse[]>([]);
  lowStock = signal<StockItemResponse[]>([]);
  productCount = signal(0);
  ordersState = signal<LoadState>('loading');
  salesState = signal<LoadState>('loading');
  salesSummary = signal<PeriodSummary | null>(null);
  stockState = signal<LoadState>('loading');
  productsState = signal<LoadState>('loading');

  private yesterday = signal<PeriodSummary | null>(null);
  trend = signal<number[]>([]);
  salesReady = computed(() => this.salesState() === 'ready' && this.salesSummary() !== null);

  /** Today's change against yesterday for each tile. */
  deltas = computed(() => {
    const t = this.salesSummary(), y = this.yesterday();
    if (!t || !y) return { net: null, orders: null, avg: null, margin: null };
    const ta = this.average(t), ya = this.average(y);
    return {
      net: pct(Number(t.netSales), Number(y.netSales)),
      orders: pct(t.orderCount, y.orderCount),
      avg: ta === null || ya === null ? null : pct(ta, ya),
      margin: pct(Number(t.grossMargin), Number(y.grossMargin)),
    };
  });

  /** Net sales per order, or null when there were no orders. */
  average(s: PeriodSummary | null): number | null {
    return s && s.orderCount > 0 ? Number(s.netSales) / s.orderCount : null;
  }

  /** Permissions arrive after sign-in settles, so each section loads when its permission shows up (and again on a branch switch). */
  constructor() {
    effect(() => { if (this.auth.can('sales:read')) untracked(() => this.loadOrders()); else this.ordersState.set('ready'); });
    effect(() => { if (this.auth.can('report:read')) untracked(() => this.loadSales()); else this.salesState.set('ready'); });
    effect(() => { if (this.auth.can('inventory:read')) untracked(() => this.loadStock()); else this.stockState.set('ready'); });
    effect(() => { if (this.auth.can('catalog:read')) untracked(() => this.loadProducts()); else this.productsState.set('ready'); });
  }

  loadOrders() {
    this.ordersState.set('loading');
    this.sales.listOrders(0, 25).subscribe({
      next: (p) => { this.orders.set(p.content); this.ordersState.set('ready'); },
      error: () => this.ordersState.set('error'),
    });
  }

  /** Today's totals come from the server summary, not from adding up the recent-orders page. */
  loadSales() {
    this.salesState.set('loading');
    const yday = reportPeriod(Date.now() - 86_400_000, 1);
    const week = reportPeriod(Date.now(), 7);
    forkJoin({
      today: this.reporting.summary(),
      yesterday: this.reporting.summary(yday.from.toISOString(), yday.to.toISOString()),
      days: this.reporting.salesByDay(week.from.toISOString(), week.to.toISOString()),
    }).subscribe({
      next: ({ today, yesterday, days }) => {
        this.salesSummary.set(today);
        this.yesterday.set(yesterday);
        this.trend.set(zeroFillDays(week.days, days).map((d) => Number(d.netSales)));
        this.salesState.set('ready');
      },
      error: () => this.salesState.set('error'),
    });
  }

  loadStock() {
    this.stockState.set('loading');
    this.inventory.lowStock().subscribe({
      next: (l) => { this.lowStock.set(l); this.stockState.set('ready'); },
      error: () => this.stockState.set('error'),
    });
  }

  loadProducts() {
    this.productsState.set('loading');
    this.catalog.listProducts(0, 1).subscribe({
      next: (p) => { this.productCount.set(p.totalElements); this.productsState.set('ready'); },
      error: () => this.productsState.set('error'),
    });
  }

  statusClass(status: string): string {
    switch (status) {
      case 'COMPLETED': case 'PAID': return 'badge-green';
      case 'OPEN': return 'badge-blue';
      case 'CANCELLED': case 'VOID': return 'badge-gray';
      case 'REFUNDED': return 'badge-amber';
      default: return 'badge-gray';
    }
  }
}
