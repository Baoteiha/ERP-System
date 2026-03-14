import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  kpis = [
    { label: 'Total Revenue', value: '₫2.4B', change: '+12.5% vs last month', up: true, icon: 'bi-currency-exchange', color: 'green' },
    { label: 'Active Orders', value: '348', change: '+8 today', up: true, icon: 'bi-receipt', color: 'blue' },
    { label: 'Low Stock Items', value: '14', change: '3 critical', up: false, icon: 'bi-exclamation-triangle', color: 'orange' },
    { label: 'Employees', value: '127', change: '2 on leave', up: true, icon: 'bi-people', color: 'blue' },
  ];

  recentOrders = [
    { id: 'ORD-2024-0891', customer: 'Nguyen Van A', amount: '₫4,250,000', status: 'Completed', date: '14 Mar 2026' },
    { id: 'ORD-2024-0890', customer: 'Tran Thi B', amount: '₫1,800,000', status: 'Pending', date: '14 Mar 2026' },
    { id: 'ORD-2024-0889', customer: 'Le Van C', amount: '₫7,320,000', status: 'Completed', date: '13 Mar 2026' },
    { id: 'ORD-2024-0888', customer: 'Pham Thi D', amount: '₫2,100,000', status: 'Processing', date: '13 Mar 2026' },
    { id: 'ORD-2024-0887', customer: 'Hoang Van E', amount: '₫950,000', status: 'Completed', date: '12 Mar 2026' },
  ];

  lowStockItems = [
    { name: 'Fresh Milk 1L', sku: 'MLK-001', stock: 8, threshold: 20, category: 'Dairy' },
    { name: 'Cocoa Powder', sku: 'COC-003', stock: 3, threshold: 15, category: 'Condiments' },
    { name: 'Strawberry Jam', sku: 'JAM-007', stock: 11, threshold: 25, category: 'Condiments' },
  ];

  activities = [
    { time: '09:42', text: 'Order ORD-0891 marked as completed', icon: 'bi-check-circle', color: 'green' },
    { time: '09:15', text: 'Stock adjusted: Fresh Milk -24 units', icon: 'bi-box-seam', color: 'orange' },
    { time: '08:50', text: 'New employee Nguyen Van F onboarded', icon: 'bi-person-plus', color: 'blue' },
    { time: '08:20', text: 'Invoice INV-0234 approved by manager', icon: 'bi-file-earmark-check', color: 'green' },
    { time: 'Yesterday', text: 'Monthly report generated', icon: 'bi-file-bar-graph', color: 'blue' },
  ];

  getStatusClass(status: string) {
    if (status === 'Completed') return 'badge-active';
    if (status === 'Pending') return 'badge-pending';
    return 'badge-inactive';
  }
}
