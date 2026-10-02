import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, forkJoin, of, switchMap } from 'rxjs';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CatalogService, InventoryService, ReportingService, SalesService } from '../../core/api.services';
import { LabelPipe, MoneyPipe, QtyPipe, ShortIdPipe } from '../../core/util';
import { IngredientResponse, OrderResponse, PeriodSummary, StockItemResponse } from '../../core/models';
import { IconComponent } from '../../shared/icon';
import { KpiTileComponent } from '../../shared/kpi-tile';
import { reportPeriod, zeroFillDays } from '../reports/reports';
import { BranchTowersComponent } from './branch-towers';

type LoadState = 'loading' | 'ready' | 'error';

/** Change of `cur` against `prev` as a fraction; null when there is no previous value to compare with. */
export function pct(cur: number, prev: number): number | null {
  return prev === 0 ? null : (cur - prev) / prev;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MoneyPipe, QtyPipe, ShortIdPipe, LabelPipe, IconComponent, KpiTileComponent, BranchTowersComponent],
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
          <app-kpi-tile label="Gross margin" icon="percent" [value]="salesReady() ? (salesSummary()!.grossMargin | money) : '—'" [delta]="deltas().margin" [trend]="trend()" />
        } @else {
          <app-kpi-tile label="Recent orders" icon="receipt" [value]="ordersState() === 'ready' ? '' + orders().length : '—'" [delta]="null" [trend]="[]" />
          <app-kpi-tile label="Active products" icon="package" [value]="productsState() === 'ready' ? '' + productCount() : '—'" [delta]="null" [trend]="[]" />
          <app-kpi-tile label="Low-stock items" icon="alert" [value]="stockState() === 'ready' ? '' + lowStock().length : '—'" [delta]="null" [trend]="[]" />
        }
      </div>

      @if (auth.can('report:read') && salesState() === 'error') {
        <div class="load-error kpi-error" role="alert">
          Couldn't load today's figures.
          <button type="button" class="btn btn-sm btn-outline" (click)="loadSales()">Retry</button>
        </div>
      }

      <section class="towers-slot">@if (canReport()) {<app-branch-towers />}</section>

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
                        <td class="mono">{{ o.id | shortId }}</td>
                        <td>{{ o.orderType | label }}</td>
                        <td><span class="badge dot" [class]="statusClass(o.status)">{{ o.status | label }}</span></td>
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
                    <span>{{ ingredientNames().get(s.ingredientId) ?? (s.ingredientId | shortId) }}</span>
                    <span class="spacer"></span>
                    <span class="num soft">{{ s.quantityOnHand | qty }}{{ unitOf(s.ingredientId) }} on hand</span>
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
    .kpi-error { margin: -.6rem 0 1rem; padding: .8rem 1rem; }
    .towers-slot { margin-bottom: 1.4rem; }
    .towers-slot:empty { display: none; }
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
  private ingredients = signal<IngredientResponse[]>([]);
  ingredientNames = computed(() => new Map(this.ingredients().map((i) => [i.id, i.name])));
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

  private destroyRef = inject(DestroyRef);
  private ordersLoad$ = new Subject<void>();
  private salesLoad$ = new Subject<void>();
  private stockLoad$ = new Subject<void>();
  private productsLoad$ = new Subject<void>();
  private canSales = computed(() => this.auth.can('sales:read'));
  protected canReport = computed(() => this.auth.can('report:read'));
  private canInventory = computed(() => this.auth.can('inventory:read'));
  private canCatalog = computed(() => this.auth.can('catalog:read'));

  /**
   * Each section loads when its permission shows up and again when the branch changes. A reload cancels the
   * request before it, so an answer for the branch we left can never overwrite the current one.
   */
  constructor() {
    this.ordersLoad$.pipe(
      switchMap(() => {
        this.ordersState.set('loading');
        return this.sales.listOrders(0, 25).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      if (p) { this.orders.set(p.content); this.ordersState.set('ready'); } else this.ordersState.set('error');
    });

    // today's totals come from the server summary, not from adding up the recent-orders page
    this.salesLoad$.pipe(
      switchMap(() => {
        this.salesState.set('loading');
        const yday = reportPeriod(Date.now() - 86_400_000, 1);
        const week = reportPeriod(Date.now(), 7);
        return forkJoin({
          today: this.reporting.summary(),
          yesterday: this.reporting.summary(yday.from.toISOString(), yday.to.toISOString()),
          days: this.reporting.salesByDay(week.from.toISOString(), week.to.toISOString()),
        }).pipe(
          switchMap(({ today, yesterday, days }) => of({ today, yesterday, trend: zeroFillDays(week.days, days).map((d) => Number(d.netSales)) })),
          catchError(() => of(null)),
        );
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((r) => {
      if (!r) { this.salesState.set('error'); return; }
      this.salesSummary.set(r.today);
      this.yesterday.set(r.yesterday);
      this.trend.set(r.trend);
      this.salesState.set('ready');
    });

    this.stockLoad$.pipe(
      switchMap(() => {
        this.stockState.set('loading');
        return forkJoin({
          low: this.inventory.lowStock(),
          // names are a nicety: without them a row falls back to the short id
          names: this.inventory.listIngredients().pipe(catchError(() => of([] as IngredientResponse[]))),
        }).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((l) => {
      if (l) { this.lowStock.set(l.low); this.ingredients.set(l.names); this.stockState.set('ready'); } else this.stockState.set('error');
    });

    this.productsLoad$.pipe(
      switchMap(() => {
        this.productsState.set('loading');
        return this.catalog.listProducts(0, 1).pipe(catchError(() => of(null)));
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((p) => {
      if (p) { this.productCount.set(p.totalElements); this.productsState.set('ready'); } else this.productsState.set('error');
    });

    // permissions arrive after sign-in settles; the branch is read on purpose so a switch reloads
    const load = (can: () => boolean, state: { set(v: LoadState): void }, go: () => void) => effect(() => {
      this.auth.activeBranchId();
      if (can()) untracked(go); else state.set('ready');
    });
    load(this.canSales, this.ordersState, () => this.loadOrders());
    load(this.canReport, this.salesState, () => this.loadSales());
    load(this.canInventory, this.stockState, () => this.loadStock());
    load(this.canCatalog, this.productsState, () => this.loadProducts());
  }

  /** The ingredient's unit with a leading space, or nothing while it is unknown. */
  unitOf(id: string): string {
    const unit = this.ingredients().find((i) => i.id === id)?.baseUnit;
    return unit ? ' ' + unit : '';
  }

  loadOrders() { this.ordersLoad$.next(); }
  loadSales() { this.salesLoad$.next(); }
  loadStock() { this.stockLoad$.next(); }
  loadProducts() { this.productsLoad$.next(); }

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
