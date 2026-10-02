import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';
import { permissionGuard } from './core/permission.guard';
import { ShellComponent } from './layout/shell';
import { LoginComponent } from './features/auth/login';

// each feature is a lazy chunk; canMatch permissions mirror the shell menu (layout/shell.ts)
export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: 'forgot-password', loadComponent: () => import('./features/auth/forgot-password').then((m) => m.ForgotPasswordComponent), canActivate: [guestGuard] },
  // unguarded: a reset link may be opened in a browser still signed in, which it then signs out
  { path: 'reset-password', loadComponent: () => import('./features/auth/reset-password').then((m) => m.ResetPasswordComponent) },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.DashboardComponent) },
      { path: 'pos', loadComponent: () => import('./features/sales/pos').then((m) => m.PosComponent), canMatch: [permissionGuard('sales:write')] },
      { path: 'orders', loadComponent: () => import('./features/sales/orders').then((m) => m.OrdersComponent), canMatch: [permissionGuard('sales:read')] },
      { path: 'catalog/products', loadComponent: () => import('./features/catalog/products').then((m) => m.ProductsComponent), canMatch: [permissionGuard('catalog:read')] },
      { path: 'catalog/categories', loadComponent: () => import('./features/catalog/categories').then((m) => m.CategoriesComponent), canMatch: [permissionGuard('catalog:read')] },
      { path: 'catalog/modifiers', loadComponent: () => import('./features/catalog/modifiers').then((m) => m.ModifiersComponent), canMatch: [permissionGuard('catalog:read')] },
      { path: 'inventory/stock', loadComponent: () => import('./features/inventory/stock').then((m) => m.StockComponent), canMatch: [permissionGuard('inventory:read')] },
      { path: 'inventory/ingredients', loadComponent: () => import('./features/inventory/ingredients').then((m) => m.IngredientsComponent), canMatch: [permissionGuard('inventory:read')] },
      { path: 'inventory/suppliers', loadComponent: () => import('./features/inventory/suppliers').then((m) => m.SuppliersComponent), canMatch: [permissionGuard('inventory:read')] },
      { path: 'inventory/items', loadComponent: () => import('./features/inventory/items').then((m) => m.ItemsComponent), canMatch: [permissionGuard('inventory:read')] },
      { path: 'inventory/purchase-orders', loadComponent: () => import('./features/inventory/purchase-orders').then((m) => m.PurchaseOrdersComponent), canMatch: [permissionGuard('purchasing:read')] },
      { path: 'staff/team', loadComponent: () => import('./features/staff/team').then((m) => m.TeamComponent), canMatch: [permissionGuard('staff:read')] },
      { path: 'staff/shifts', loadComponent: () => import('./features/staff/shifts').then((m) => m.ShiftsComponent), canMatch: [permissionGuard('staff:read')] },
      { path: 'reports', loadComponent: () => import('./features/reports/reports').then((m) => m.ReportsComponent), canMatch: [permissionGuard('report:read')] },
      { path: 'org/companies', loadComponent: () => import('./features/organization/companies').then((m) => m.CompaniesComponent), canMatch: [permissionGuard('company:read')] },
      { path: 'org/branches', loadComponent: () => import('./features/organization/branches').then((m) => m.BranchesComponent), canMatch: [permissionGuard('branch:read')] },
      { path: 'org/users', loadComponent: () => import('./features/organization/users').then((m) => m.UsersComponent), canMatch: [permissionGuard('user:read')] },
      { path: 'org/roles', loadComponent: () => import('./features/organization/roles').then((m) => m.RolesComponent), canMatch: [permissionGuard('role:read')] },
    ],
  },
  { path: '**', redirectTo: '' },
];
