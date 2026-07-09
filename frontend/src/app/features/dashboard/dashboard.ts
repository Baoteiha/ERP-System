import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CatalogService, InventoryService, SalesService } from '../../core/api.services';
import { MoneyPipe } from '../../core/util';
import { OrderResponse, StockItemResponse } from '../../core/models';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink, MoneyPipe],
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1>Dashboard</h1>
          <div class="sub">{{ greeting() }} — here's the state of your active branch.</div>
        </div>
      </div>

      <div class="kpis">
        <div class="kpi">
          <div class="kpi-ic" style="background:var(--green-soft);color:var(--green)">₫</div>
          <div><div class="kpi-val">{{ salesToday() | money }}</div><div class="kpi-lbl">Sales (recent orders)</div></div>
        </div>
        <div class="kpi">
          <div class="kpi-ic" style="background:var(--blue-soft);color:var(--blue)">🧾</div>
          <div><div class="kpi-val">{{ orders().length }}</div><div class="kpi-lbl">Recent orders</div></div>
        </div>
        <div class="kpi">
          <div class="kpi-ic" style="background:var(--violet-soft);color:var(--violet)">🥤</div>
          <div><div class="kpi-val">{{ productCount() }}</div><div class="kpi-lbl">Active products</div></div>
        </div>
        <div class="kpi">
          <div class="kpi-ic" style="background:var(--red-soft);color:var(--red)">⚠</div>
          <div><div class="kpi-val">{{ lowStock().length }}</div><div class="kpi-lbl">Low-stock items</div></div>
        </div>
      </div>

      <div class="dash-grid">
        @if (auth.can('sales:read')) {
          <div class="card">
            <div class="card-head"><h3>Recent orders</h3><a class="btn btn-sm btn-outline" routerLink="/orders">View all</a></div>
            <div class="table-wrap">
              <table class="data">
                <thead><tr><th>Order</th><th>Type</th><th>Status</th><th class="num">Total</th></tr></thead>
                <tbody>
                  @for (o of orders().slice(0, 8); track o.id) {
                    <tr>
                      <td class="mono">#{{ o.id.slice(0,6) }}</td>
                      <td>{{ o.orderType }}</td>
                      <td><span class="badge" [class]="statusClass(o.status)">{{ o.status }}</span></td>
                      <td class="num">{{ o.grandTotal | money }}</td>
                    </tr>
                  } @empty { <tr><td colspan="4" class="muted" style="text-align:center;padding:2rem">No orders yet</td></tr> }
                </tbody>
              </table>
            </div>
          </div>
        }

        @if (auth.can('inventory:read')) {
          <div class="card">
            <div class="card-head"><h3>Low stock alerts</h3><a class="btn btn-sm btn-outline" routerLink="/inventory/stock">Manage</a></div>
            <div class="card-pad">
              @for (s of lowStock().slice(0, 8); track s.ingredientId) {
                <div class="low-row">
                  <span class="mono">{{ s.ingredientId.slice(0,6) }}</span>
                  <span class="spacer"></span>
                  <span class="num soft">{{ s.quantityOnHand }} on hand</span>
                  <span class="badge badge-red">reorder {{ s.reorderLevel }}</span>
                </div>
              } @empty { <div class="empty" style="padding:1.5rem"><div class="big">✓</div>All stock above reorder level</div> }
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1.1rem; margin-bottom: 1.4rem; }
    .kpi { display: flex; align-items: center; gap: 1rem; padding: 1.2rem 1.3rem;
      background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm); }
    .kpi-ic { width: 46px; height: 46px; border-radius: 12px; display: flex; align-items: center;
      justify-content: center; font-size: 20px; font-weight: 700; flex-shrink: 0; }
    .kpi-val { font-size: 22px; font-weight: 700; letter-spacing: -.01em; }
    .kpi-lbl { font-size: 12.5px; color: var(--text-muted); margin-top: 2px; }
    .dash-grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 1.2rem; align-items: start; }
    .low-row { display: flex; align-items: center; gap: .7rem; padding: .55rem 0; border-bottom: 1px solid var(--border); font-size: 13px; }
    .low-row:last-child { border-bottom: none; }
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
    if (this.auth.can('sales:read')) {
      this.sales.listOrders(0, 25).subscribe({ next: (p) => this.orders.set(p.content), error: () => {} });
    }
    if (this.auth.can('inventory:read')) {
      this.inventory.lowStock().subscribe({ next: (l) => this.lowStock.set(l), error: () => {} });
    }
    if (this.auth.can('catalog:read')) {
      this.catalog.listProducts(0, 1).subscribe({ next: (p) => this.productCount.set(p.totalElements), error: () => {} });
    }
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
