import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-landing',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './landing.html',
})
export class Landing {
  features = [
    { icon: 'bi-box-seam', title: 'Inventory Management', desc: 'Track stock levels, set low-stock alerts, and manage food products with ingredient-level visibility.' },
    { icon: 'bi-receipt', title: 'Sales & Orders', desc: 'Process sales orders, manage customers, and track fulfilment from one unified interface.' },
    { icon: 'bi-wallet2', title: 'Finance & Accounting', desc: 'Monitor revenue, expenses, and net profit. Generate invoices and track outstanding payments.' },
    { icon: 'bi-people', title: 'HR & Employees', desc: 'Manage your workforce, track attendance, and handle onboarding all in one place.' },
    { icon: 'bi-bar-chart-line', title: 'Analytics & Reports', desc: 'Visualise business performance with real-time charts across sales, stock, and financials.' },
    { icon: 'bi-shield-check', title: 'Role-Based Access', desc: 'Granular ABAC permissions ensure each team member sees only what they need.' },
  ];

  stats = [
    { value: '99.9%', label: 'Uptime' },
    { value: '< 200ms', label: 'API Response' },
    { value: 'ABAC', label: 'Security Model' },
    { value: 'Real-time', label: 'Stock Tracking' },
  ];
}
