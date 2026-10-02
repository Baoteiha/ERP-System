import type { AuthService } from '../core/auth.service';
/** A sidebar link: where it goes, its icon, and the permissions that reveal it. */
export interface NavItem { label: string; icon: string; path: string; perms?: string[]; }
/** A titled group of sidebar links. */
export interface NavSection { title: string; items: NavItem[]; }

/** The sidebar sections shared by the shell and the command palette. */
export const NAV_SECTIONS: NavSection[] = [
  { title: 'Overview', items: [
    { label: 'Dashboard', path: '/', icon: 'grid' },
  ]},
  { title: 'Point of Sale', items: [
    { label: 'New Order', path: '/pos', perms: ['sales:write'], icon: 'cart' },
    { label: 'Orders', path: '/orders', perms: ['sales:read'], icon: 'receipt' },
  ]},
  { title: 'Catalog', items: [
    { label: 'Products', path: '/catalog/products', perms: ['catalog:read'], icon: 'package' },
    { label: 'Categories', path: '/catalog/categories', perms: ['catalog:read'], icon: 'tag' },
    { label: 'Modifiers', path: '/catalog/modifiers', perms: ['catalog:read'], icon: 'sliders' },
  ]},
  { title: 'Inventory', items: [
    { label: 'Stock', path: '/inventory/stock', perms: ['inventory:read'], icon: 'box' },
    { label: 'Ingredients', path: '/inventory/ingredients', perms: ['inventory:read'], icon: 'flask' },
    { label: 'Suppliers', path: '/inventory/suppliers', perms: ['inventory:read'], icon: 'truck' },
    { label: 'Items', path: '/inventory/items', perms: ['inventory:read'], icon: 'tag' },
    { label: 'Purchase Orders', path: '/inventory/purchase-orders', perms: ['purchasing:read'], icon: 'clipboard' },
  ]},
  { title: 'Staff', items: [
    { label: 'Team', path: '/staff/team', perms: ['staff:read'], icon: 'users' },
    { label: 'Shifts', path: '/staff/shifts', perms: ['staff:read'], icon: 'clock' },
  ]},
  { title: 'Insights', items: [
    { label: 'Reports', path: '/reports', perms: ['report:read'], icon: 'trending-up' },
  ]},
  { title: 'Organization', items: [
    { label: 'Companies', path: '/org/companies', perms: ['company:read'], icon: 'building' },
    { label: 'Branches', path: '/org/branches', perms: ['branch:read'], icon: 'pin' },
    { label: 'Users', path: '/org/users', perms: ['user:read'], icon: 'user' },
    { label: 'Roles', path: '/org/roles', perms: ['role:read'], icon: 'key' },
  ]},
];

/** The sections whose links the user may open; reads `me` so it follows permission changes. */
export function visibleNav(auth: AuthService): NavSection[] {
  auth.me();
  return NAV_SECTIONS
    .map((s) => ({ ...s, items: s.items.filter((i) => !i.perms || auth.canAny(...i.perms)) }))
    .filter((s) => s.items.length > 0);
}
