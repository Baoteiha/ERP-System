import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-sidebar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './sidebar.html'
})
export class Sidebar {
  navSections = [
    {
      label: 'Main',
      items: [
        { label: 'Dashboard', icon: 'bi-grid-fill', route: '/dashboard' },
      ]
    },
    {
      label: 'Operations',
      items: [
        { label: 'Sales', icon: 'bi-receipt', route: '/sales' },
        { label: 'Inventory', icon: 'bi-box-seam', route: '/inventory' },
        { label: 'Finance', icon: 'bi-wallet2', route: '/finance' },
      ]
    },
    {
      label: 'People',
      items: [
        { label: 'Employees', icon: 'bi-people', route: '/employees' },
      ]
    },
    {
      label: 'Insights',
      items: [
        { label: 'Analytics', icon: 'bi-bar-chart-line', route: '/analytics' },
        { label: 'Settings', icon: 'bi-gear', route: '/settings' },
      ]
    }
  ];
}
