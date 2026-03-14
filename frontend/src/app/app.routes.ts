import { Routes } from '@angular/router';
import { authGuard } from './guards/auth-guard';
import { Landing } from './pages/landing/landing';
import { Login } from './pages/login/login';
import { Register } from './pages/register/register';
import { Dashboard } from './pages/dashboard/dashboard';
import { Inventory } from './pages/inventory/inventory';
import { Finance } from './pages/finance/finance';
import { Employees } from './pages/employees/employees';
import { Sales } from './pages/sales/sales';
import { Analytics } from './pages/analytics/analytics';
import { Settings } from './pages/settings/settings';

export const routes: Routes = [
  { path: '', component: Landing },
  { path: 'login', component: Login },
  { path: 'register', component: Register },
  { path: 'dashboard', component: Dashboard, canActivate: [authGuard] },
  { path: 'inventory', component: Inventory, canActivate: [authGuard] },
  { path: 'finance', component: Finance, canActivate: [authGuard] },
  { path: 'employees', component: Employees, canActivate: [authGuard] },
  { path: 'sales', component: Sales, canActivate: [authGuard] },
  { path: 'analytics', component: Analytics, canActivate: [authGuard] },
  { path: 'settings', component: Settings, canActivate: [authGuard] },
];
