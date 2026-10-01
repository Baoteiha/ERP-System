import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, debounceTime, map, of, switchMap, tap } from 'rxjs';
import { CatalogService, SalesService } from '../../core/api.services';
import { AuthService } from '../../core/auth.service';
import { CategoryResponse, CreateOrderRequest, MenuModifierGroup, MenuProduct, Money, OrderResponse, OrderType, PaymentMethod } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { MoneyPipe, ShortIdPipe, formatMoney } from '../../core/util';
import { ModalComponent } from '../../shared/modal';
import { IconComponent } from '../../shared/icon';
import { SteamComponent } from '../../shared/steam';

interface CartLine {
  product: MenuProduct;
  quantity: number;
  modifierIds: string[];
  lineDiscount: string;
}

@Component({
  selector: 'app-pos',
  standalone: true,
  imports: [FormsModule, MoneyPipe, ShortIdPipe, ModalComponent, IconComponent, SteamComponent],
  template: `
    <div class="pos">
      <section class="menu counter-lip">
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
          <input class="input search" [ngModel]="search()" (ngModelChange)="search.set($event)" placeholder="Search products or SKU" aria-label="Search products or SKU" />
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
              <button class="product-tile paper-card" (click)="addProduct(p)">
                <span class="sku">{{ p.sku }}</span>
                <b>{{ p.name }}</b>
                <span class="price"><app-icon name="tag" [size]="12" aria-hidden="true" /> {{ p.effectivePrice | money }}</span>
              </button>
            } @empty { <div class="empty"><div class="big"><app-icon name="bean" [size]="30" /></div>No products match</div> }
          </div>
        }
      </section>

      <aside class="ticket">
        <div class="printer-slot">
          <span class="ps-cup cup-steamed" aria-hidden="true"><app-icon name="coffee" [size]="17" /><app-steam /></span>
          <div><b>Current ticket</b><div class="sub mini">{{ cart().length }} line(s)</div></div>
          <span class="spacer"></span>
          <button class="btn btn-sm btn-ghost" (click)="clearCart()" [disabled]="cart().length === 0">Clear</button>
        </div>

        <div class="paper thermal thermal-edge">
        <div class="cart-lines">
          @for (line of cart(); track line.product.id + $index; let idx = $index) {
            <div class="cart-line">
              <div class="line-main">
                <div>
                  <b>{{ line.product.name }}</b>
                  @if (line.modifierIds.length) { <div class="soft">{{ modifierNames(line).join(', ') }}</div> }
                </div>
                <button class="btn btn-sm btn-ghost btn-icon" (click)="removeLine(idx)" [attr.aria-label]="'Remove ' + line.product.name"><app-icon name="close" [size]="16" /></button>
              </div>
              <div class="line-tools">
                <button class="qty" (click)="changeQty(idx, -1)" aria-label="Decrease quantity"><app-icon name="minus" [size]="16" /></button>
                <span>{{ line.quantity }}</span>
                <button class="qty" (click)="changeQty(idx, 1)" aria-label="Increase quantity"><app-icon name="plus" [size]="16" /></button>
                <button class="btn btn-sm btn-outline" [class.needs-opts]="unmetGroups(line).length" (click)="configure(idx)">
                  {{ unmetGroups(line).length ? 'Choose options' : 'Modifiers' }}
                </button>
                <input class="input discount" type="number" min="0" [ngModel]="line.lineDiscount" (ngModelChange)="setLineDiscount(idx, $event)" placeholder="Discount" [attr.aria-label]="'Discount for ' + line.product.name" />
              </div>
              <div class="line-total">{{ lineTotalAt(idx) | money }}</div>
            </div>
          } @empty {
            <div class="empty">
              <div class="big pos-cup"><app-icon name="cup" [size]="30" /><app-steam class="cup-steam" /></div>
              Add products to start an order
            </div>
          }
        </div>

        <div class="checkout">
          <div><span>Subtotal</span><b>{{ displayQuote()?.subtotal | money }}</b></div>
          <div><span>Discounts</span><b>{{ displayQuote()?.discountTotal | money }}</b></div>
          <div>
            <label for="pos-discount">Order discount</label>
            <input id="pos-discount" class="input money-input" type="number" min="0" [ngModel]="orderDiscount()" (ngModelChange)="setOrderDiscount($event)" />
          </div>
          <div>
            <span>Tax ({{ taxPercent() }}%)</span>
            <b>{{ displayQuote()?.taxTotal | money }}</b>
          </div>
          <div class="grand"><span>Total</span><b>{{ displayQuote()?.grandTotal | money }}</b></div>
        </div>
        </div>

        @if (cart().length && !ticketValid()) {
          <div class="pay-hint" role="status">Some items still need options — tap “Choose options” on the amber lines.</div>
        }
        <div class="pay-strip">
          <select class="select" [(ngModel)]="paymentMethod" aria-label="Payment method">
            <option value="CASH">Cash</option>
            <option value="CARD">Card</option>
            <option value="EWALLET">E-wallet</option>
          </select>
          <button class="btn btn-primary" (click)="submit()" [disabled]="saving() || !payable() || !auth.can('sales:write')">
            @if (saving()) { <span class="spinner"></span> } Pay {{ displayQuote()?.grandTotal | money }}
          </button>
        </div>
      </aside>
    </div>

    @if (configuringIndex() !== null) {
      <app-modal title="Choose modifiers" (close)="configuringIndex.set(null)">
        @if (currentLine(); as line) {
          @for (group of groupsFor(line.product); track group.id) {
            <div class="modifier-group">
              <div class="group-title" [class.unmet]="isUnmet(line, group)">
                <b>{{ group.name }}</b>
                <span class="g-hint">{{ selectionHint(group) }}</span>
              </div>
              <div class="modifier-options">
                @for (m of group.modifiers; track m.id) {
                  <label class="option leader-row">
                    <input type="checkbox" [checked]="line.modifierIds.includes(m.id)" (change)="toggleModifier(m.id, group)" />
                    <span>{{ m.name }}</span>
                    <span class="dots" aria-hidden="true"></span>
                    <b>{{ m.priceDelta | money }}</b>
                  </label>
                }
              </div>
            </div>
          } @empty { <div class="empty"><div class="big"><app-icon name="sliders" [size]="30" /></div>This product has no modifiers</div> }
        }
        <div footer>
          <button class="btn btn-primary" (click)="configuringIndex.set(null)">Done</button>
        </div>
      </app-modal>
    }

    @if (createdOrder(); as order) {
      <app-modal [title]="'Order ' + order.status" (close)="createdOrder.set(null)">
        <div class="receipt thermal thermal-edge thermal-edge-top print-in">
          @if (order.status === 'PAID' || order.status === 'COMPLETED') { <span class="stamp stamp-in r-stamp" aria-hidden="true">Paid</span> }
          <div class="r-head"><b>{{ order.id | shortId }}</b><span>{{ order.orderType }}</span></div>
          <div><span>Paid</span><b>{{ order.amountPaid | money:order.currency }}</b></div>
          <div><span>Total</span><b>{{ order.grandTotal | money:order.currency }}</b></div>
          <div class="r-glee" aria-hidden="true">
            <span class="r-cup cup-steamed"><app-icon name="cup" [size]="18" /><app-steam /></span>
          </div>
        </div>
        <div footer>
          <button class="btn btn-primary" (click)="newOrder()">New ticket</button>
        </div>
      </app-modal>
    }
  `,
  styles: [`
    .pos { min-height: calc(100vh - 70px); display: grid; grid-template-columns: minmax(0, 1fr) 420px; gap: 1rem; padding: 1rem; }
    .menu, .ticket { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 1.15rem; box-shadow: var(--shadow-sm); }
    /* counter scene */
    .menu { background: linear-gradient(180deg, var(--surface-2), var(--bg) 85%); padding-bottom: 2.1rem; }
    .pos-head, .line-main, .checkout > div, .receipt > div { display: flex; justify-content: space-between; gap: 1rem; align-items: center; }
    .pos-head h1 { margin: 0; font-size: 22px; }
    .sub { color: var(--text-soft); font-size: 13.5px; margin-top: .2rem; }
    .sub.mini { font-size: 12px; margin-top: 0; }
    .toolbar { display: grid; gap: .75rem; margin: 1rem 0; }
    .search { max-width: 440px; }
    /* segmented */
    .segmented button { min-height: 44px; font-size: 12.5px; }
    .segmented button.active { background: var(--brand); color: #fff; box-shadow: 0 1px 2px rgba(120,53,15,.25), inset 0 -2px 0 var(--gold); }
    .category-strip { display: flex; flex-wrap: wrap; gap: .4rem; }
    .category-strip button { border: 1px solid var(--border); background: var(--surface); color: var(--text-soft); border-radius: 999px; padding: .45rem .8rem; min-height: 44px; font: inherit; font-size: 12.5px; font-weight: 600; cursor: pointer; transition: background .15s, color .15s, border-color .15s; }
    .category-strip button:hover { border-color: var(--brand); color: var(--text); }
    .category-strip button.active { background: var(--brand); color: #fff; border-color: var(--brand); box-shadow: inset 0 -2px 0 var(--gold); }
    .product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: .75rem; }
    /* menu cards */
    .product-tile { min-height: 128px; text-align: left; border-radius: var(--radius); padding: .9rem; display: grid; align-content: space-between; gap: .3rem; cursor: pointer; font: inherit; transition: transform var(--dur-2) var(--ease-spring), box-shadow var(--dur-1); }
    .product-tile:hover { transform: translateY(-3px); box-shadow: var(--shadow-md); }
    .product-tile:active { transform: translateY(-1px) scale(.98); }
    .product-tile:disabled { cursor: not-allowed; transform: none; box-shadow: none; }
    .product-tile b { font-size: 14px; line-height: 1.3; color: var(--paper-ink); }
    .sku { font-family: var(--font-mono); font-size: .72rem; color: var(--paper-ink-soft); }
    .price { display: inline-flex; align-items: center; gap: .3rem; justify-self: end; font-family: var(--font-mono); font-weight: 700; color: var(--paper-accent); font-variant-numeric: tabular-nums; }
    .price app-icon { color: var(--gold-600); }
    /* ticket */
    .ticket { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; gap: 0; padding: 0; position: sticky; top: 1rem; max-height: calc(100vh - 2rem); }
    .printer-slot .sub.mini { color: var(--nav-text); }
    .ps-cup { color: var(--nav-text-hi); }
    .ps-cup app-steam { color: var(--nav-text); }
    .paper { margin: 0 10px 14px; padding: 1rem 1rem 1.1rem; display: grid; grid-template-rows: minmax(120px, 1fr) auto; gap: .9rem;
      box-shadow: 0 2px 6px rgba(40,24,10,.18); }
    .cart-lines { overflow: auto; display: grid; align-content: start; gap: .75rem; }
    .cart-line { border-bottom: 1px dashed var(--paper-line); padding-bottom: .75rem; }
    .cart-line .soft { color: var(--paper-ink-soft); }
    .pos-cup { position: relative; }
    .cup-steam { position: absolute; top: 5px; left: 50%; transform: translateX(-50%); color: var(--brand); }
    .paper .qty { background: rgba(255,255,255,.55); border-color: var(--paper-line); color: var(--paper-ink); }
    .cart-line b { font-size: 13.5px; }
    .line-tools { display: grid; grid-template-columns: 44px 26px 44px 1fr 95px; gap: .35rem; align-items: center; margin-top: .55rem; }
    .qty { height: 44px; width: 44px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--border-strong); background: var(--surface); color: var(--text); border-radius: var(--radius-sm); cursor: pointer; transition: background .12s, border-color .12s; }
    .qty:hover { background: var(--surface-2); border-color: var(--brand); }
    .discount, .money-input { min-width: 0; min-height: 44px; }
    .line-total { text-align: right; margin-top: .35rem; font-family: var(--font-mono); font-weight: 700; font-variant-numeric: tabular-nums; }
    .checkout { border-top: 1px dashed var(--paper-line); padding-top: .75rem; display: grid; gap: .5rem; font-size: 13.5px; }
    .checkout span, .checkout label { color: var(--paper-ink-soft); font-weight: 600; }
    .checkout b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
    .money-input { width: 110px; text-align: right; }
    .grand { font-size: 1.25rem; padding-top: .6rem; border-top: 3px double var(--paper-line); }
    .grand span { color: var(--paper-ink); font-weight: 600; }
    .grand b { color: var(--paper-accent); font-weight: 800; }
    .pay-strip { display: grid; grid-template-columns: 130px 1fr; gap: .5rem; padding: 0 1.05rem 1.05rem; }
    .pay-strip .btn-primary { font-size: 14.5px; min-height: 48px;
      background: linear-gradient(180deg, color-mix(in srgb, var(--brand) 85%, #fff), var(--brand)); }
    .pay-strip .btn-primary:hover { background: var(--brand-600); }
    @media (prefers-color-scheme: dark) {
      .pay-strip .btn-primary { background: var(--brand-700); }
      .pay-strip .btn-primary:hover { background: color-mix(in srgb, var(--brand-700) 85%, #fff); }
    }
    .modifier-group { margin-bottom: 1rem; }
    .group-title { display: flex; justify-content: space-between; gap: 1rem; margin-bottom: .5rem;
      border-bottom: 2px solid rgba(185,28,28,.25); padding-bottom: .35rem; }
    .modifier-options { display: grid; gap: .45rem; }
    .option { gap: .5rem; align-items: center; min-height: 44px; padding: .45rem .7rem; border: 1px solid var(--border); border-radius: var(--radius-sm); cursor: pointer; transition: border-color .12s, background .12s; }
    .option .dots { border-color: var(--border-strong); }
    .option:hover { border-color: var(--brand); background: var(--brand-soft); }
    .option b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
    /* pay-success receipt */
    .receipt { display: grid; gap: .3rem; padding: 1.1rem 1rem; margin: 10px 4px 14px;
      box-shadow: 0 2px 6px rgba(40,24,10,.18); }
    .receipt > div { border-bottom: 1px dashed var(--paper-line); padding: .3rem 0; }
    .receipt b { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
    .r-head { font-family: var(--font-mono); }
    .r-head span { color: var(--paper-ink-soft); font-size: 12.5px; }
    .r-stamp { position: absolute; top: 6px; right: 12px; color: #963120; }
    .r-glee { border-bottom: none; justify-content: center; padding-top: .55rem; }
    .r-cup { color: var(--paper-ink-soft); }
    @media (max-width: 1050px) { .pos { grid-template-columns: 1fr; } .ticket { position: static; max-height: none; } }
  `],
})
export class PosComponent {
  auth = inject(AuthService);
  private catalog = inject(CatalogService);
  private sales = inject(SalesService);
  private toast = inject(ToastService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  categories = signal<CategoryResponse[]>([]);
  products = signal<MenuProduct[]>([]);
  cart = signal<CartLine[]>([]);
  loading = signal(true);
  saving = signal(false);
  orderType = signal<OrderType>('DINE_IN');
  categoryId = signal<string>('all');
  configuringIndex = signal<number | null>(null);
  createdOrder = signal<OrderResponse | null>(null);
  search = signal('');
  orderDiscount = signal('0');
  paymentMethod: PaymentMethod = 'CASH';

  filteredProducts = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.products().filter((p) =>
      (this.categoryId() === 'all' || p.categoryId === this.categoryId()) &&
      (!q || p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q))
    );
  });

  ticketValid = computed(() => this.cart().every((l) => this.unmetGroups(l).length === 0));

  /** What the server is asked to price; null while there is nothing sellable to price. */
  private quoteRequest = computed<CreateOrderRequest | null>(() => {
    if (!this.cart().length || !this.ticketValid()) return null;
    return {
      orderType: this.orderType(),
      orderDiscount: this.orderDiscount() || '0',
      lines: this.cart().map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
        modifierIds: line.modifierIds,
        lineDiscount: line.lineDiscount || '0',
      })),
    };
  });

  /** The server's answer and the request it answered; the totals on screen are never computed here. */
  private quoted = signal<{ request: CreateOrderRequest; order: OrderResponse } | null>(null);
  /** The last priced ticket, shown even for the moment before a newer quote lands. */
  displayQuote = computed(() => this.quoted()?.order ?? null);
  /** The quote for exactly what is in the cart now, or null while a newer one is pending. */
  currentQuote = computed(() => {
    const q = this.quoted();
    return q && q.request === this.quoteRequest() ? q.order : null;
  });
  payable = computed(() => this.currentQuote() !== null);
  taxPercent = computed(() => String(Math.round(Number(this.displayQuote()?.taxRate || 0) * 10000) / 100));

  /** One id per attempt to sell the current cart: a retry of a lost create then returns the same order. */
  private clientRequestId: string | null = null;
  /** The order created for this attempt while it awaits payment, so a retry pays it instead of creating another. */
  private awaitingPayment: OrderResponse | null = null;

  constructor() {
    this.load();
    toObservable(this.quoteRequest).pipe(
      // a changed cart is a different sale: it gets its own request id and order
      tap(() => {
        // the unpaid order made for the old cart must not linger OPEN at the branch
        if (this.awaitingPayment) this.sales.cancel(this.awaitingPayment.id).subscribe({ error: () => undefined });
        this.clientRequestId = null;
        this.awaitingPayment = null;
      }),
      debounceTime(250),
      // switchMap cancels the quote still in flight, so a slow old answer never overwrites a newer one
      switchMap((request) => request === null
        ? of(null)
        : this.sales.quote(request).pipe(
            map((order) => ({ request, order })),
            catchError(() => of(null)),
          )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((quoted) => this.quoted.set(quoted));
  }

  load() {
    this.loading.set(true);
    this.catalog.listCategories().subscribe({ next: (items) => this.categories.set(items.filter((c) => c.active)) });
    this.catalog.menu().subscribe({
      next: (items) => { this.products.set(items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  addProduct(product: MenuProduct) {
    if (!product.modifierGroups.length) {
      const existingIdx = this.cart().findIndex((line) =>
        line.product.id === product.id &&
        line.modifierIds.length === 0 &&
        Number(line.lineDiscount || 0) === 0
      );
      if (existingIdx >= 0) {
        this.changeQty(existingIdx, 1);
        return;
      }
    }

    this.cart.update((cart) => [...cart, { product, quantity: 1, modifierIds: [], lineDiscount: '0' }]);
    const idx = this.cart().length - 1;
    if (product.modifierGroups.length) this.configuringIndex.set(idx);
  }
  removeLine(idx: number) { this.cart.update((cart) => cart.filter((_, i) => i !== idx)); }
  changeQty(idx: number, delta: number) {
    this.cart.update((cart) => cart.map((line, i) => i === idx ? { ...line, quantity: Math.max(1, line.quantity + delta) } : line));
  }
  setLineDiscount(idx: number, value: string | number | null) {
    this.cart.update((cart) => cart.map((line, i) => i === idx ? { ...line, lineDiscount: String(value ?? '') } : line));
  }
  setOrderDiscount(value: string | number | null) { this.orderDiscount.set(String(value ?? '')); }
  clearCart() { this.cart.set([]); }
  configure(idx: number) { this.configuringIndex.set(idx); }
  currentLine() { const idx = this.configuringIndex(); return idx === null ? null : this.cart()[idx] ?? null; }
  groupsFor(product: MenuProduct) { return product.modifierGroups; }
  modifierNames(line: CartLine) {
    const modifiers = line.product.modifierGroups.flatMap((g) => g.modifiers);
    return line.modifierIds.map((id) => modifiers.find((m) => m.id === id)?.name ?? id.slice(0, 6));
  }

  /** The server's total for the line, once a quote that includes it has landed. */
  lineTotalAt(idx: number): Money | null {
    const quote = this.displayQuote();
    return quote && quote.lines.length === this.cart().length ? quote.lines[idx].lineTotal : null;
  }

  /** Modifier groups of a line that still need choices (selected < min). */
  unmetGroups(line: CartLine): MenuModifierGroup[] {
    return this.groupsFor(line.product).filter((g) => this.isUnmet(line, g));
  }
  isUnmet(line: CartLine, group: MenuModifierGroup): boolean {
    const chosen = group.modifiers.filter((m) => line.modifierIds.includes(m.id)).length;
    return chosen < group.minSelect;
  }

  /** Plain-language selection rule for a group ("Choose exactly 1"). */
  selectionHint(g: MenuModifierGroup): string {
    if (g.minSelect <= 0) return g.maxSelect > 0 ? `Optional · up to ${g.maxSelect}` : 'Optional';
    if (g.minSelect === g.maxSelect) return `Choose exactly ${g.minSelect}`;
    return `Choose ${g.minSelect}–${g.maxSelect}`;
  }

  toggleModifier(modifierId: string, group: MenuModifierGroup) {
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
    if (!this.ticketValid()) {
      const idx = this.cart().findIndex((l) => this.unmetGroups(l).length > 0);
      this.toast.error('Choose the required options first');
      if (idx >= 0) this.configuringIndex.set(idx);
      return;
    }
    const request = this.quoteRequest();
    if (!request || !this.payable()) return;
    this.saving.set(true);
    if (this.awaitingPayment) {
      this.pay(this.awaitingPayment);
      return;
    }
    this.clientRequestId ??= crypto.randomUUID();
    this.sales.createOrder({ ...request, clientRequestId: this.clientRequestId }).subscribe({
      next: (order) => {
        // a zero-total ticket is already completed at creation; there is nothing to pay
        if (order.status !== 'OPEN') {
          this.paid(order);
          return;
        }
        this.awaitingPayment = order;
        this.pay(order);
      },
      error: () => this.saving.set(false),
    });
  }

  private pay(order: OrderResponse) {
    this.sales.addPayment(order.id, { method: this.paymentMethod, amount: order.grandTotal }).subscribe({
      next: (paid) => this.paid(paid),
      error: () => this.saving.set(false),
    });
  }

  private paid(order: OrderResponse) {
    this.clientRequestId = null;
    this.awaitingPayment = null;
    this.saving.set(false);
    this.createdOrder.set(order);
    this.toast.success(`Paid ${formatMoney(order.grandTotal, order.currency)}`);
  }

  newOrder() {
    this.createdOrder.set(null);
    this.clearCart();
    this.orderDiscount.set('0');
    this.router.navigateByUrl('/pos');
  }
}
