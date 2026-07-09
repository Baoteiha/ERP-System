/** Mirror of backend vn.essvn.erpcafe.identity.domain.Permissions catalog. */
export interface PermGroup { label: string; perms: { key: string; desc: string }[]; }

export const PERMISSION_GROUPS: PermGroup[] = [
  { label: 'Organization', perms: [
    { key: 'company:read', desc: 'View companies' },
    { key: 'company:write', desc: 'Create/update companies' },
    { key: 'branch:read', desc: 'View branches' },
    { key: 'branch:write', desc: 'Create/update branches' },
  ]},
  { label: 'Access', perms: [
    { key: 'user:read', desc: 'View users' },
    { key: 'user:write', desc: 'Create/update users, grant access' },
    { key: 'role:read', desc: 'View roles' },
    { key: 'role:write', desc: 'Create/update roles' },
  ]},
  { label: 'Catalog', perms: [
    { key: 'catalog:read', desc: 'View menu, products, recipes, modifiers' },
    { key: 'catalog:write', desc: 'Create/update menu, products, recipes, modifiers' },
  ]},
  { label: 'Inventory & Purchasing', perms: [
    { key: 'inventory:read', desc: 'View ingredients, suppliers, stock, movements' },
    { key: 'inventory:write', desc: 'Create/update ingredients, suppliers, adjustments' },
    { key: 'purchasing:read', desc: 'View purchase orders' },
    { key: 'purchasing:write', desc: 'Create/manage purchase orders and receiving' },
  ]},
  { label: 'Sales', perms: [
    { key: 'sales:read', desc: 'View orders' },
    { key: 'sales:write', desc: 'Create orders, take payments, complete' },
    { key: 'sales:refund', desc: 'Void and refund orders' },
  ]},
];

export const ALL_PERMISSIONS = PERMISSION_GROUPS.flatMap((g) => g.perms.map((p) => p.key));
