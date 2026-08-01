import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CatalogService, InventoryService, SalesService } from '../../core/api.services';
import { MoneyPipe, ShortIdPipe } from '../../core/util';
import { OrderResponse, StockItemResponse } from '../../core/models';
import { IconComponent } from '../../shared/icon';
import { SteamComponent } from '../../shared/steam';

type LoadState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MoneyPipe, ShortIdPipe, IconComponent, SteamComponent],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Dashboard</h1>
          <div class="greet">
            <span class="script g-script">{{ greeting() }}</span>
            <span class="g-cup cup-steamed" aria-hidden="true"><app-icon name="coffee" [size]="17" /><app-steam /></span>
          </div>
          <div class="sub">Here's the state of your active branch.</div>
        </div>
      </div>

      <div class="kpis">
        <div class="kpi grain">
          <div class="kpi-ic" style="background:var(--green-soft);color:var(--green)" aria-hidden="true">
            <app-icon name="banknote" [size]="22" />
          </div>
          <div><div class="kpi-val">{{ ordersState() === 'ready' ? (salesToday() | money) : '—' }}</div><div class="kpi-lbl">Sales (recent orders)</div></div>
        </div>
        <div class="kpi grain">
          <div class="kpi-ic" style="background:var(--blue-soft);color:var(--blue)" aria-hidden="true">
            <app-icon name="receipt" [size]="22" />
          </div>
          <div><div class="kpi-val">{{ ordersState() === 'ready' ? orders().length : '—' }}</div><div class="kpi-lbl">Recent orders</div></div>
        </div>
        <div class="kpi grain">
          <div class="kpi-ic" style="background:var(--violet-soft);color:var(--violet)" aria-hidden="true">
            <app-icon name="coffee" [size]="22" />
          </div>
          <div><div class="kpi-val">{{ productsState() === 'ready' ? productCount() : '—' }}</div><div class="kpi-lbl">Active products</div></div>
        </div>
        <div class="kpi grain">
          <div class="kpi-ic" style="background:var(--red-soft);color:var(--red)" aria-hidden="true">
            <app-icon name="alert" [size]="22" />
          </div>
          <div><div class="kpi-val">{{ stockState() === 'ready' ? lowStock().length : '—' }}</div><div class="kpi-lbl">Low-stock items</div></div>
        </div>
      </div>

      <div class="dash-grid">
        @if (auth.can('sales:read')) {
          <div class="card">
            <div class="card-head"><h2>Recent orders</h2><a class="btn btn-sm btn-outline" routerLink="/orders">View all</a></div>
            @if (ordersState() === 'loading') {
              <div class="brewing"><span class="brew-cup cup-steamed"><app-icon name="cup" [size]="20" /><app-steam /></span> Brewing…</div>
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
                <div class="brewing"><span class="brew-cup cup-steamed"><app-icon name="cup" [size]="20" /><app-steam /></span> Checking stock…</div>
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
                    <div class="big all-good"><app-icon name="leaf" [size]="30" /></div>
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
    .greet { display: flex; align-items: center; gap: .7rem; margin-top: .1rem; }
    .g-script { font-size: 24px; color: var(--brand); line-height: 1.1; }
    .g-cup { color: var(--brand); }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1.1rem; margin-bottom: 1.4rem; }
    /* cork coasters: rim ring + grain overlay (global .grain) */
    .kpi { position: relative; display: flex; align-items: center; gap: 1rem; padding: 1.35rem 1.45rem;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
      box-shadow: var(--shadow-sm), inset 0 0 0 6px color-mix(in srgb, var(--wood-mid) 20%, var(--surface));
      overflow: hidden; transition: box-shadow .15s, transform .15s; }
    .kpi:hover { box-shadow: var(--shadow-md), inset 0 0 0 6px color-mix(in srgb, var(--wood-mid) 20%, var(--surface)); transform: translateY(-1px); }
    .kpi-ic { width: 46px; height: 46px; border-radius: 12px; display: flex; align-items: center;
      justify-content: center; flex-shrink: 0; }
    .kpi-val { font-family: var(--font-mono); font-size: 22px; font-weight: 700; letter-spacing: -.01em; font-variant-numeric: tabular-nums; }
    .kpi-lbl { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; }
    .dash-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 1.2rem; align-items: start; }
    .low-row { display: flex; align-items: center; gap: .7rem; padding: .55rem 0; border-bottom: 1px solid var(--border); font-size: 13px; }
    .low-row:last-child { border-bottom: none; }
    .empty-sm { padding: 1.6rem 1rem; }
    .empty-sm .big.all-good { color: var(--green); }
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

  orders = signal<OrderResponse[]>([]);
  lowStock = signal<StockItemResponse[]>([]);
  productCount = signal(0);
  ordersState = signal<LoadState>('loading');
  stockState = signal<LoadState>('loading');
  productsState = signal<LoadState>('loading');

  salesToday = computed(() =>
    this.orders()
      .filter((o) => ['PAID', 'COMPLETED'].includes(o.status))
      .reduce((sum, o) => sum + Number(o.grandTotal || 0), 0),
  );

  greeting = computed(() => {
    const h = new Date().getHours();
    const t = h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
    return t;
  });

  constructor() {
    if (this.auth.can('sales:read')) this.loadOrders(); else this.ordersState.set('ready');
    if (this.auth.can('inventory:read')) this.loadStock(); else this.stockState.set('ready');
    if (this.auth.can('catalog:read')) this.loadProducts(); else this.productsState.set('ready');
  }

  loadOrders() {
    this.ordersState.set('loading');
    this.sales.listOrders(0, 25).subscribe({
      next: (p) => { this.orders.set(p.content); this.ordersState.set('ready'); },
      error: () => this.ordersState.set('error'),
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
