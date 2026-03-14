import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-sales',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './sales.html',
  styleUrl: './sales.scss',
})
export class Sales {
  orders = [
    { id: 'ORD-2026-0891', customer: 'Nguyen Van A', items: 5, amount: '₫4,250,000', status: 'Completed', date: '14 Mar 2026' },
    { id: 'ORD-2026-0890', customer: 'Tran Thi B', items: 2, amount: '₫1,800,000', status: 'Pending', date: '14 Mar 2026' },
    { id: 'ORD-2026-0889', customer: 'Le Van C', items: 8, amount: '₫7,320,000', status: 'Completed', date: '13 Mar 2026' },
    { id: 'ORD-2026-0888', customer: 'Pham Thi D', items: 3, amount: '₫2,100,000', status: 'Processing', date: '13 Mar 2026' },
    { id: 'ORD-2026-0887', customer: 'Hoang Van E', items: 1, amount: '₫950,000', status: 'Completed', date: '12 Mar 2026' },
  ];
  getStatusClass(s: string) {
    if (s === 'Completed') return 'badge-active';
    if (s === 'Pending') return 'badge-pending';
    return 'badge-inactive';
  }
}
