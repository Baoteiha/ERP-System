import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';
import { ShellComponent } from './layout/shell';
import { LoginComponent } from './features/auth/login';
import { DashboardComponent } from './features/dashboard/dashboard';
import { CompaniesComponent } from './features/organization/companies';
import { BranchesComponent } from './features/organization/branches';
import { UsersComponent } from './features/organization/users';
import { RolesComponent } from './features/organization/roles';
import { CategoriesComponent } from './features/catalog/categories';
import { ProductsComponent } from './features/catalog/products';
import { ModifiersComponent } from './features/catalog/modifiers';
import { IngredientsComponent } from './features/inventory/ingredients';
import { SuppliersComponent } from './features/inventory/suppliers';
import { StockComponent } from './features/inventory/stock';
import { PurchaseOrdersComponent } from './features/inventory/purchase-orders';
import { OrdersComponent } from './features/sales/orders';
import { PosComponent } from './features/sales/pos';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardComponent },
      { path: 'pos', component: PosComponent },
      { path: 'orders', component: OrdersComponent },
      { path: 'catalog/products', component: ProductsComponent },
      { path: 'catalog/categories', component: CategoriesComponent },
      { path: 'catalog/modifiers', component: ModifiersComponent },
      { path: 'inventory/stock', component: StockComponent },
      { path: 'inventory/ingredients', component: IngredientsComponent },
      { path: 'inventory/suppliers', component: SuppliersComponent },
      { path: 'inventory/purchase-orders', component: PurchaseOrdersComponent },
      { path: 'org/companies', component: CompaniesComponent },
      { path: 'org/branches', component: BranchesComponent },
      { path: 'org/users', component: UsersComponent },
      { path: 'org/roles', component: RolesComponent },
    ],
  },
  { path: '**', redirectTo: '' },
];
