import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { Chart, CategoryScale, LinearScale, BarElement, LineElement, PointElement, ArcElement, Title, Tooltip, Legend, Filler } from 'chart.js';

Chart.register(CategoryScale, LinearScale, BarElement, LineElement, PointElement, ArcElement, Title, Tooltip, Legend, Filler);

@Component({
  selector: 'app-finance',
  standalone: true,
  imports: [CommonModule, BaseChartDirective],
  templateUrl: './finance.html',
  styleUrl: './finance.scss',
})
export class Finance {
  activeTab = 'overview';

  kpis = [
    { label: 'Monthly Revenue', value: '₫2.4B', change: '+12.5%', up: true, icon: 'bi-graph-up-arrow', color: 'green' },
    { label: 'Monthly Expenses', value: '₫1.1B', change: '-3.2%', up: true, icon: 'bi-wallet2', color: 'orange' },
    { label: 'Net Profit', value: '₫1.3B', change: '+28.1%', up: true, icon: 'bi-currency-exchange', color: 'blue' },
    { label: 'Pending Invoices', value: '23', change: '₫340M outstanding', up: false, icon: 'bi-file-earmark-text', color: 'orange' },
  ];

  invoices = [
    { id: 'INV-0234', customer: 'Nguyen Van A', amount: '₫4,250,000', due: '20 Mar 2026', status: 'Pending' },
    { id: 'INV-0233', customer: 'Tran Thi B', amount: '₫1,800,000', due: '18 Mar 2026', status: 'Paid' },
    { id: 'INV-0232', customer: 'Le Van C', amount: '₫7,320,000', due: '15 Mar 2026', status: 'Overdue' },
    { id: 'INV-0231', customer: 'Pham Thi D', amount: '₫2,100,000', due: '30 Mar 2026', status: 'Paid' },
    { id: 'INV-0230', customer: 'Hoang Van E', amount: '₫950,000', due: '25 Mar 2026', status: 'Pending' },
  ];

  // Revenue vs Expenses — grouped bar
  revenueExpenseData = {
    labels: ['Oct', 'Nov', 'Dec', 'Jan', 'Feb', 'Mar'],
    datasets: [
      { label: 'Revenue', data: [1800, 2100, 2400, 1950, 2200, 2400], backgroundColor: '#3A8C4A' },
      { label: 'Expenses', data: [900, 1050, 1200, 980, 1050, 1100], backgroundColor: '#F4871E' },
    ]
  };
  revenueExpenseOptions = {
    responsive: true,
    plugins: { legend: { position: 'top' as const } },
    scales: { y: { beginAtZero: true, grid: { color: '#F0F0F0' } }, x: { grid: { display: false } } }
  };

  // Expense breakdown — Doughnut
  expenseBreakdownData = {
    labels: ['Cost of Goods', 'Staff', 'Utilities', 'Marketing', 'Logistics', 'Other'],
    datasets: [{
      data: [42, 28, 10, 8, 7, 5],
      backgroundColor: ['#3A8C4A', '#F4871E', '#1B6BAA', '#2D6A38', '#F9B36B', '#A8C5B0'],
      borderWidth: 2,
      borderColor: '#fff',
    }]
  };
  expenseBreakdownOptions = {
    responsive: true,
    plugins: { legend: { position: 'right' as const } }
  };

  getStatusClass(s: string) {
    if (s === 'Paid') return 'badge-active';
    if (s === 'Overdue') return 'badge-pending';
    return 'badge-inactive';
  }
}
