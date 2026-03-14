import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ProductService, ProductTypeService, Product, ProductType } from '../../services/product';

@Component({
  selector: 'app-inventory',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './inventory.html',
  styleUrl: './inventory.scss',
})
export class Inventory implements OnInit {
  products: Product[] = [];
  productTypes: ProductType[] = [];
  loading = false;
  error = '';

  newTypeName = '';
  newTypeDescription = '';
  newTypeIcon = 'bi-box-seam';
  typeFormError = '';
  typeFormLoading = false;

  readonly iconOptions = [
    'bi-box-seam', 'bi-egg-fried', 'bi-cup-hot', 'bi-droplet',
    'bi-snow2', 'bi-basket', 'bi-bag', 'bi-cart',
    'bi-wrench', 'bi-laptop', 'bi-brush', 'bi-house',
    'bi-truck', 'bi-heart-pulse', 'bi-flower1', 'bi-star',
  ];

  constructor(
    private productService: ProductService,
    private productTypeService: ProductTypeService,
  ) {}

  ngOnInit() {
    this.loading = true;
    this.productService.getProducts().subscribe({
      next: (res) => {
        this.products = res.data;
        this.loading = false;
      },
      error: (err) => {
        this.error = err.error?.message || 'Failed to load inventory';
        this.loading = false;
      },
    });
    this.loadProductTypes();
  }

  loadProductTypes() {
    this.productTypeService.getAll().subscribe({
      next: (res) => { this.productTypes = res.data; },
    });
  }

  createProductType() {
    if (!this.newTypeName.trim()) return;
    this.typeFormLoading = true;
    this.typeFormError = '';
    this.productTypeService.create(this.newTypeName.trim(), this.newTypeIcon, this.newTypeDescription.trim() || undefined).subscribe({
      next: (res) => {
        this.productTypes = [...this.productTypes, res.data];
        this.newTypeName = '';
        this.newTypeDescription = '';
        this.newTypeIcon = 'bi-box-seam';
        this.typeFormLoading = false;
      },
      error: (err) => {
        this.typeFormError = err.error?.message || 'Failed to create product type';
        this.typeFormLoading = false;
      },
    });
  }

  deleteProductType(id: string) {
    this.productTypeService.delete(id).subscribe({
      next: () => { this.productTypes = this.productTypes.filter(t => t._id !== id); },
    });
  }

  productCountForType(typeId: string) {
    return this.products.filter(p => p.productType?._id === typeId).length;
  }

  get totalProducts() { return this.products.length; }

  get totalValue() {
    return this.products.reduce((sum, p) => sum + p.price * p.stock, 0);
  }

  get lowStockItems() {
    return this.products.filter(p => p.stock > 0 && p.stock <= p.lowStockThreshold);
  }

  get outOfStockItems() {
    return this.products.filter(p => p.stock === 0);
  }

  get categoryBreakdown() {
    const map = new Map<string, number>();
    for (const p of this.products) {
      const key = p.category || p.productType?.name || 'General';
      map.set(key, (map.get(key) ?? 0) + 1);
    }
    return Array.from(map.entries())
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count);
  }

  get statusBreakdown() {
    const active = this.products.filter(p => p.status === 'active').length;
    const inactive = this.products.filter(p => p.status === 'inactive').length;
    const discontinued = this.products.filter(p => p.status === 'discontinued').length;
    return [
      { label: 'Active', count: active, color: '#3A8C4A' },
      { label: 'Inactive', count: inactive, color: '#9CA3AF' },
      { label: 'Discontinued', count: discontinued, color: '#EF4444' },
    ];
  }

  get topValueProducts() {
    return [...this.products]
      .sort((a, b) => b.price * b.stock - a.price * a.stock)
      .slice(0, 5);
  }

  stockPercent(p: Product) {
    if (p.lowStockThreshold === 0) return 100;
    return Math.min(100, Math.round((p.stock / (p.lowStockThreshold * 2)) * 100));
  }

  stockBarColor(p: Product) {
    if (p.stock === 0) return '#EF4444';
    if (p.stock <= p.lowStockThreshold) return '#F4871E';
    return '#3A8C4A';
  }
}
