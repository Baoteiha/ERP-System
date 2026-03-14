import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';

export interface ProductType {
  _id: string;
  name: string;
  description?: string;
  icon: string;
}

export interface Product {
  _id: string;
  name: string;
  sku: string;
  description?: string;
  price: number;
  unit: string;
  stock: number;
  lowStockThreshold: number;
  supplier?: string;
  status: string;
  productType?: ProductType | null;
  category?: string;
  expiryTracking?: boolean;
  shelfLifeDays?: number;
  ingredients?: any[];
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class ProductTypeService {
  constructor(private http: HttpClient) {}

  getAll() {
    return this.http.get<{ success: boolean; data: ProductType[] }>('/api/product-types');
  }

  create(name: string, icon: string, description?: string) {
    return this.http.post<{ success: boolean; data: ProductType }>('/api/product-types', { name, icon, description });
  }

  delete(id: string) {
    return this.http.delete<{ success: boolean }>(`/api/product-types/${id}`);
  }
}

@Injectable({ providedIn: 'root' })
export class ProductService {
  constructor(private http: HttpClient) {}

  getProducts(filters: { status?: string; category?: string; lowStock?: string } = {}) {
    let params = new HttpParams();
    if (filters.status) params = params.set('status', filters.status);
    if (filters.category) params = params.set('category', filters.category);
    if (filters.lowStock) params = params.set('lowStock', filters.lowStock);
    return this.http.get<{ success: boolean; data: Product[] }>('/api/products', { params });
  }

  getProductById(id: string) {
    return this.http.get<{ success: boolean; data: Product }>(`/api/products/${id}`);
  }

  createProduct(data: Partial<Product>) {
    return this.http.post<{ success: boolean; data: Product }>('/api/products', data);
  }

  updateProduct(id: string, data: Partial<Product>) {
    return this.http.patch<{ success: boolean; data: Product }>(`/api/products/${id}`, data);
  }

  adjustStock(id: string, delta: number) {
    return this.http.patch<{ success: boolean; data: Product }>(`/api/products/${id}/stock`, { delta });
  }

  deleteProduct(id: string) {
    return this.http.delete<{ success: boolean }>(`/api/products/${id}`);
  }
}
