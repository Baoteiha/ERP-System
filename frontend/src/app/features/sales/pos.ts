import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CatalogService, SalesService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { CategoryResponse, LineRequest, ModifierGroupResponse, OrderResponse, OrderType, PaymentMethod, ProductResponse } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, formatMoney } from '../../core/util';
import { ModalComponent } from '../../shared/modal';

interface CartLine {
  product: ProductResponse;
  quantity: number;
  modifierIds: string[];
  lineDiscount: string;
}

@Component({
  selector: 'app-pos',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ModalComponent],
  template: `
    <div class="pos">
      <section class="menu">
        <div class="pos-head">
          <div>
            <h1>POS</h1>
            <div class="sub">Build orders for the active branch.</div>
          </div>
          <div class="segmented">
            <button [class.active]="orderType() === 'DINE_IN'" (click)="orderType.set('DINE_IN')">Dine in</button>
            <button [class.active]="orderType() === 'TAKEAWAY'" (click)="orderType.set('TAKEAWAY')">Takeaway</button>
          </div>
        </div>

        <div class="toolbar">
          <input class="input search" [(ngModel)]="search" placeholder="Search products or SKU" />
          <div class="category-strip">
            <button [class.active]="categoryId() === 'all'" (click)="categoryId.set('all')">All</button>
            @for (c of categories(); track c.id) {
              <button [class.active]="categoryId() === c.id" (click)="categoryId.set(c.id)">{{ c.name }}</button>
            }
          </div>
        </div>

        @if (loading()) { <div class="loading-block"><span class="spinner"></span> Loading…</div> }
        @else {
          <div class="product-grid">
            @for (p of filteredProducts(); track p.id) {
              <button class="product-tile" [disabled]="!p.active" (click)="addProduct(p)">
                <span class="sku">{{ p.sku }}</span>
                <b>{{ p.name }}</b>
                <span class="price">{{ p.basePrice | money }}</span>
              </button>
            } @empty { <div class="empty"><div class="big">☕</div>No products match</div> }
          </div>
        }
      </section>

      <aside class="ticket">
        <div class="ticket-head">
          <div><b>Current ticket</b><div class="sub mini">{{ cart().length }} line(s)</div></div>
          <button class="btn btn-sm btn-ghost" (click)="clearCart()" [disabled]="cart().length === 0">Clear</button>
        </div>

        <div class="cart-lines">
          @for (line of cart(); track line.product.id + $index; let idx = $index) {
            <div class="cart-line">
              <div class="line-main">
                <div>
                  <b>{{ line.product.name }}</b>
                  @if (line.modifierIds.length) { <div class="soft">{{ modifierNames(line.modifierIds).join(', ') }}</div> }
                </div>
                <button class="btn btn-sm btn-ghost" (click)="removeLine(idx)">×</button>
              </div>
              <div class="line-tools">
                <button class="qty" (click)="changeQty(idx, -1)">−</button>
                <span>{{ line.quantity }}</span>
                <button class="qty" (click)="changeQty(idx, 1)">＋</button>
                <button class="btn btn-sm btn-outline" (click)="configure(idx)">Modifiers</button>
                <input class="input discount" type="number" min="0" [(ngModel)]="line.lineDiscount" placeholder="Discount" />
              </div>
              <div class="line-total">{{ lineTotal(line) | money }}</div>
            </div>
          } @empty { <div class="empty"><div class="big">🧺</div>Add products to start an order</div> }
        </div>

        <div class="checkout">
          <div><span>Subtotal</span><b>{{ subtotal() | money }}</b></div>
          <div><span>Line discounts</span><b>{{ lineDiscountTotal() | money }}</b></div>
          <div>
            <span>Order discount</span>
            <input class="input money-input" type="number" min="0" [(ngModel)]="orderDiscount" />
          </div>
          <div>
            <span>Tax rate</span>
            <input class="input money-input" type="number" min="0" step="0.01" [(ngModel)]="taxRate" />
          </div>
          <div class="grand"><span>Total</span><b>{{ grandTotal() | money }}</b></div>
        </div>

        <div class="pay-strip">
          <select class="input" [(ngModel)]="paymentMethod">
            <option value="CASH">Cash</option>
            <option value="CARD">Card</option>
            <option value="EWALLET">E-wallet</option>
          </select>
          <button class="btn btn-primary" (click)="submit()" [disabled]="saving() || cart().length === 0 || !auth.can('sales:write')">
            @if (saving()) { <span class="spinner"></span> } Pay {{ grandTotal() | money }}
          </button>
        </div>
      </aside>
    </div>

    @if (configuringIndex() !== null) {
      <app-modal title="Choose modifiers" (close)="configuringIndex.set(null)">
        @if (currentLine(); as line) {
          @for (group of groupsFor(line.product); track group.id) {
            <div class="modifier-group">
              <div class="group-title">
                <b>{{ group.name }}</b>
                <span class="soft">{{ group.minSelect }}–{{ group.maxSelect }} option(s)</span>
              </div>
              <div class="modifier-options">
                @for (m of group.modifiers; track m.id) {
                  <label class="option">
                    <input type="checkbox" [checked]="line.modifierIds.includes(m.id)" (change)="toggleModifier(m.id, group)" />
                    <span>{{ m.name }}</span>
                    <b>{{ m.priceDelta | money }}</b>
                  </label>
                }
              </div>
            </div>
          } @empty { <div class="empty"><div class="big">➕</div>This product has no modifiers</div> }
        }
        <div footer>
          <button class="btn btn-primary" (click)="configuringIndex.set(null)">Done</button>
        </div>
      </app-modal>
    }

    @if (createdOrder(); as order) {
      <app-modal [title]="'Order ' + order.status" (close)="createdOrder.set(null)">
        <div class="receipt">
          <div><b>{{ order.id.slice(0, 8) }}</b><span>{{ order.orderType }}</span></div>
          <div><span>Paid</span><b>{{ order.amountPaid | money:order.currency }}</b></div>
          <div><span>Total</span><b>{{ order.grandTotal | money:order.currency }}</b></div>
        </div>
        <div footer>
          @if (order.status === 'PAID') { <button class="btn btn-outline" (click)="complete(order)">Complete</button> }
          <button class="btn btn-primary" (click)="newOrder()">New ticket</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .pos { min-height: calc(100vh - 70px); display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 1rem; padding: 1rem; }
    .menu, .ticket { background: var(--surface); border: 1px solid var(--line); border-radius: 8px; padding: 1rem; }
    .pos-head, .ticket-head, .line-main, .checkout > div, .receipt > div { display: flex; justify-content: space-between; gap: 1rem; align-items: center; }
    .pos-head h1 { margin: 0; }
    .toolbar { display: grid; gap: .75rem; margin: 1rem 0; }
    .search { max-width: 440px; }
    .category-strip { display: flex; flex-wrap: wrap; gap: .4rem; }
    .category-strip button { border: 1px solid var(--line); background: #fff; border-radius: 999px; padding: .45rem .75rem; cursor: pointer; }
    .category-strip button.active { background: var(--ink); color: #fff; border-color: var(--ink); }
    .product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: .75rem; }
    .product-tile { min-height: 128px; text-align: left; border: 1px solid var(--line); background: #fff; border-radius: 8px; padding: .85rem; display: grid; align-content: space-between; cursor: pointer; transition: transform .12s ease, box-shadow .12s ease; }
    .product-tile:hover { transform: translateY(-1px); box-shadow: var(--shadow-sm); }
    .product-tile:disabled { opacity: .45; cursor: not-allowed; transform: none; }
    .sku { font-size: .75rem; color: var(--ink-muted); }
    .price { font-weight: 800; color: var(--accent); }
    .ticket { display: grid; grid-template-rows: auto minmax(220px, 1fr) auto auto; gap: 1rem; position: sticky; top: 1rem; max-height: calc(100vh - 2rem); }
    .cart-lines { overflow: auto; display: grid; align-content: start; gap: .75rem; }
    .cart-line { border-bottom: 1px solid var(--line); padding-bottom: .75rem; }
    .line-tools { display: grid; grid-template-columns: 34px 26px 34px 1fr 95px; gap: .35rem; align-items: center; margin-top: .55rem; }
    .qty { height: 34px; border: 1px solid var(--line); background: #fff; border-radius: 6px; cursor: pointer; }
    .discount, .money-input { min-width: 0; }
    .line-total { text-align: right; margin-top: .35rem; font-weight: 800; }
    .checkout { border-top: 1px solid var(--line); padding-top: .75rem; display: grid; gap: .45rem; }
    .money-input { width: 110px; text-align: right; }
    .grand { font-size: 1.2rem; padding-top: .55rem; border-top: 1px solid var(--line); }
    .pay-strip { display: grid; grid-template-columns: 130px 1fr; gap: .5rem; }
    .modifier-group { margin-bottom: 1rem; }
    .group-title { display: flex; justify-content: space-between; gap: 1rem; margin-bottom: .5rem; }
    .modifier-options { display: grid; gap: .45rem; }
    .option { display: grid; grid-template-columns: 20px 1fr auto; gap: .5rem; align-items: center; padding: .55rem; border: 1px solid var(--line); border-radius: 6px; }
    .receipt { display: grid; gap: .5rem; }
    @media (max-width: 1050px) { .pos { grid-template-columns: 1fr; } .ticket { position: static; max-height: none; } }
  `],
})
export class PosComponent {
  auth = inject(AuthService);
  private catalog = inject(CatalogService);
  private sales = inject(SalesService);
  private toast = inject(ToastService);
  private router = inject(Router);

  categories = signal<CategoryResponse[]>([]);
  products = signal<ProductResponse[]>([]);
  modifierGroups = signal<ModifierGroupResponse[]>([]);
  cart = signal<CartLine[]>([]);
  loading = signal(true);
  saving = signal(false);
  orderType = signal<OrderType>('DINE_IN');
  categoryId = signal<string>('all');
  configuringIndex = signal<number | null>(null);
  createdOrder = signal<OrderResponse | null>(null);
  search = '';
  taxRate = '0';
  orderDiscount = '0';
  paymentMethod: PaymentMethod = 'CASH';

  filteredProducts = computed(() => {
    const q = this.search.trim().toLowerCase();
    return this.products().filter((p) =>
      (this.categoryId() === 'all' || p.categoryId === this.categoryId()) &&
      (!q || p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q))
    );
  });
  subtotal = computed(() => this.cart().reduce((sum, line) => sum + Number(line.product.basePrice.amount) * line.quantity + this.modifierTotal(line) * line.quantity, 0).toString());
  lineDiscountTotal = computed(() => this.cart().reduce((sum, line) => sum + Number(line.lineDiscount || 0), 0).toString());
  grandTotal = computed(() => {
    const taxable = Math.max(0, Number(this.subtotal()) - Number(this.lineDiscountTotal()) - Number(this.orderDiscount || 0));
    return (taxable + taxable * Number(this.taxRate || 0)).toString();
  });

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.catalog.listCategories().subscribe({ next: (items) => this.categories.set(items.filter((c) => c.active)) });
    this.catalog.listModifierGroups().subscribe({ next: (items) => this.modifierGroups.set(items) });
    this.catalog.listProducts(0, 500).subscribe({
      next: (page) => { this.products.set(page.content); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  addProduct(product: ProductResponse) {
    this.cart.update((cart) => [...cart, { product, quantity: 1, modifierIds: [], lineDiscount: '0' }]);
    const idx = this.cart().length - 1;
    if (product.modifierGroupIds.length) this.configuringIndex.set(idx);
  }
  removeLine(idx: number) { this.cart.update((cart) => cart.filter((_, i) => i !== idx)); }
  changeQty(idx: number, delta: number) {
    this.cart.update((cart) => cart.map((line, i) => i === idx ? { ...line, quantity: Math.max(1, line.quantity + delta) } : line));
  }
  clearCart() { this.cart.set([]); }
  configure(idx: number) { this.configuringIndex.set(idx); }
  currentLine() { const idx = this.configuringIndex(); return idx === null ? null : this.cart()[idx] ?? null; }
  groupsFor(product: ProductResponse) { return this.modifierGroups().filter((g) => product.modifierGroupIds.includes(g.id)); }
  modifierNames(ids: string[]) { return ids.map((id) => this.modifierGroups().flatMap((g) => g.modifiers).find((m) => m.id === id)?.name ?? id.slice(0, 6)); }
  modifierTotal(line: CartLine) {
    const modifiers = this.modifierGroups().flatMap((g) => g.modifiers);
    return line.modifierIds.reduce((sum, id) => sum + Number(modifiers.find((m) => m.id === id)?.priceDelta.amount ?? 0), 0);
  }
  lineTotal(line: CartLine) { return Math.max(0, (Number(line.product.basePrice.amount) + this.modifierTotal(line)) * line.quantity - Number(line.lineDiscount || 0)).toString(); }

  toggleModifier(modifierId: string, group: ModifierGroupResponse) {
    const idx = this.configuringIndex();
    if (idx === null) return;
    this.cart.update((cart) => cart.map((line, i) => {
      if (i !== idx) return line;
      const inGroup = group.modifiers.map((m) => m.id);
      const selectedInGroup = line.modifierIds.filter((id) => inGroup.includes(id));
      const selected = line.modifierIds.includes(modifierId);
      if (selected) return { ...line, modifierIds: line.modifierIds.filter((id) => id !== modifierId) };
      if (group.maxSelect > 0 && selectedInGroup.length >= group.maxSelect) {
        this.toast.error(`Only ${group.maxSelect} option(s) allowed for ${group.name}`);
        return line;
      }
      return { ...line, modifierIds: [...line.modifierIds, modifierId] };
    }));
  }

  submit() {
    const lines: LineRequest[] = this.cart().map((line) => ({
      productId: line.product.id,
      quantity: line.quantity,
      modifierIds: line.modifierIds,
      lineDiscount: line.lineDiscount || '0',
    }));
    if (!lines.length) return;
    this.saving.set(true);
    this.sales.createOrder({ orderType: this.orderType(), taxRate: this.taxRate || '0', orderDiscount: this.orderDiscount || '0', lines }).subscribe({
      next: (order) => {
        this.sales.addPayment(order.id, { method: this.paymentMethod, amount: order.grandTotal }).subscribe({
          next: (paid) => { this.saving.set(false); this.createdOrder.set(paid); this.toast.success(`Paid ${formatMoney(paid.grandTotal, paid.currency)}`); },
          error: () => this.saving.set(false),
        });
      },
      error: () => this.saving.set(false),
    });
  }

  complete(order: OrderResponse) {
    this.sales.complete(order.id).subscribe({ next: (updated) => { this.createdOrder.set(updated); this.toast.success('Order completed'); } });
  }

  newOrder() {
    this.createdOrder.set(null);
    this.clearCart();
    this.orderDiscount = '0';
    this.taxRate = '0';
    this.router.navigateByUrl('/pos');
  }
}
