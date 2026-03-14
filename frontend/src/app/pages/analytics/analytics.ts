import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BaseChartDirective } from 'ng2-charts';
import { Chart, CategoryScale, LinearScale, BarElement, LineElement, PointElement, ArcElement, Title, Tooltip, Legend, Filler } from 'chart.js';

Chart.register(CategoryScale, LinearScale, BarElement, LineElement, PointElement, ArcElement, Title, Tooltip, Legend, Filler);

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, BaseChartDirective],
  templateUrl: './analytics.html',
  styleUrl: './analytics.scss',
})
export class Analytics {
  // Monthly Revenue — Line chart
  revenueData = {
    labels: ['Oct', 'Nov', 'Dec', 'Jan', 'Feb', 'Mar'],
    datasets: [{
      label: 'Revenue (₫M)',
      data: [1800, 2100, 2400, 1950, 2200, 2400],
      borderColor: '#3A8C4A',
      backgroundColor: 'rgba(58,140,74,0.08)',
      fill: true,
      tension: 0.4,
      pointBackgroundColor: '#3A8C4A',
      pointRadius: 4,
    }]
  };
  revenueOptions = {
    responsive: true,
    plugins: { legend: { display: false } },
    scales: { y: { beginAtZero: false, grid: { color: '#F0F0F0' } }, x: { grid: { display: false } } }
  };

  // Sales by Category — Doughnut chart
  categoryData = {
    labels: ['Dairy', 'Meat', 'Beverages', 'Bakery', 'Seafood', 'Other'],
    datasets: [{
      data: [28, 22, 18, 14, 10, 8],
      backgroundColor: ['#3A8C4A', '#F4871E', '#1B6BAA', '#2D6A38', '#F9B36B', '#A8C5B0'],
      borderWidth: 2,
      borderColor: '#fff',
    }]
  };
  categoryOptions = {
    responsive: true,
    plugins: { legend: { position: 'right' as const } }
  };

  // Stock Levels — Bar chart
  stockData = {
    labels: ['Dairy', 'Meat', 'Beverages', 'Bakery', 'Seafood', 'Condiments'],
    datasets: [
      { label: 'Current Stock', data: [420, 185, 310, 95, 60, 140], backgroundColor: '#3A8C4A' },
      { label: 'Low Stock Threshold', data: [200, 100, 150, 50, 50, 80], backgroundColor: '#F4871E' },
    ]
  };
  stockOptions = {
    responsive: true,
    plugins: { legend: { position: 'top' as const } },
    scales: { y: { beginAtZero: true, grid: { color: '#F0F0F0' } }, x: { grid: { display: false } } }
  };

  // Top Products — Horizontal Bar
  topProductsData = {
    labels: ['Fresh Milk 1L', 'Orange Juice', 'Whole Chicken', 'Basmati Rice', 'Sourdough Bread'],
    datasets: [{
      label: 'Units Sold',
      data: [1240, 980, 875, 760, 620],
      backgroundColor: ['#3A8C4A', '#F4871E', '#1B6BAA', '#2D6A38', '#F9B36B'],
    }]
  };
  topProductsOptions = {
    indexAxis: 'y' as const,
    responsive: true,
    plugins: { legend: { display: false } },
    scales: { x: { beginAtZero: true, grid: { color: '#F0F0F0' } }, y: { grid: { display: false } } }
  };
}
