/* TypeScript mirrors of the backend wire DTOs (vn.essvn.erpcafe.*.web). */

export type UUID = string;

export interface Money { amount: string; currency: string; }

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/* RFC-7807 ProblemDetail from GlobalExceptionHandler */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  /** machine-readable, on every error the API returns (e.g. BRANCH_FORBIDDEN) */
  code?: string;
  [key: string]: unknown;
}

/* ---- identity / auth -------------------------------------------------- */
export interface LoginRequest { email: string; password: string; }
export interface TokenResponse { accessToken: string; tokenType: string; }
export interface MeResponse {
  userId: UUID;
  email: string;
  companyId: UUID;
  branchIds: UUID[];
  roles: string[];
  /** held at any branch */
  permissions: string[];
  /** what the user's role at each branch allows */
  permissionsByBranch: Record<UUID, string[]>;
}

export type UserStatus = 'ACTIVE' | 'DISABLED';
export interface UserResponse {
  id: UUID; companyId: UUID; email: string; fullName: string;
  status: UserStatus; createdAt: string; createdBy: string;
}
export interface CreateUserRequest { email: string; password: string; fullName?: string; }
export interface UpdateUserRequest { email: string; fullName?: string; }
export interface UpdateStatusRequest { status: UserStatus; }
export interface ChangePasswordRequest { currentPassword: string; newPassword: string; }
export interface ResetPasswordRequest { newPassword: string; }
/** the token comes from the link mailed by POST /auth/password-reset/request */
export interface PasswordResetConfirmRequest { token: string; newPassword: string; }
export interface BranchAccessResponse { branchId: UUID; roleId: UUID; }
export interface GrantAccessRequest { branchId: UUID; roleId: UUID; }

export interface RoleResponse {
  id: UUID; companyId: UUID; name: string; description?: string; permissions: string[];
}
export interface CreateRoleRequest { name: string; description?: string; permissions: string[]; }
export interface PermissionsRequest { permissions: string[]; }

/* ---- organization ----------------------------------------------------- */
export interface CompanyResponse {
  id: UUID; name: string; code: string;
  createdAt: string; createdBy: string; updatedAt: string; updatedBy: string;
}
export interface CompanyRequest { name: string; code: string; }

export interface BranchResponse {
  id: UUID; companyId: UUID; name: string; code: string;
  address?: string; phone?: string; active: boolean;
  /** Fraction 0..1 (0.08 = 8%); every order at the branch uses it. */
  taxRate: string;
  createdAt: string; createdBy: string; updatedAt: string; updatedBy: string;
}
/** The owning company is never client-supplied: the API takes it from the access token. */
export interface BranchRequest {
  name: string; code: string;
  address?: string; phone?: string; active?: boolean;
  taxRate?: string;
}

/* ---- catalog ---------------------------------------------------------- */
export interface CategoryResponse {
  id: UUID; companyId: UUID; name: string; displayOrder: number; active: boolean;
}
export interface CategoryRequest { name: string; displayOrder: number; active?: boolean; }

export interface ProductResponse {
  id: UUID; companyId: UUID; categoryId: UUID; name: string; sku: string;
  description?: string; basePrice: Money; active: boolean; modifierGroupIds: UUID[];
}
export interface CreateProductRequest {
  categoryId: UUID; name: string; sku: string; basePrice: string; description?: string;
}
export interface UpdateProductRequest extends CreateProductRequest { active: boolean; }
export interface SetModifierGroupsRequest { modifierGroupIds: UUID[]; }

export interface BranchAvailabilityRequest { branchId: UUID; available: boolean; priceOverride?: string | null; }
export interface BranchAvailabilityResponse { branchId: UUID; available: boolean; priceOverride?: Money; }
export interface PriceResponse { productId: UUID; branchId: UUID; price: Money; }

export interface ConsumptionLine { ingredientId: UUID; quantity: string; unit: string; }
export interface RecipeResponse { productId: UUID; lines: ConsumptionLine[]; version: number; }
// version echoes the loaded recipe so a stale save is rejected (409); absent on the first save.
export interface SetRecipeRequest { lines: ConsumptionLine[]; version?: number; }

export interface ModifierResponse {
  id: UUID; name: string; priceDelta: Money; displayOrder: number; active: boolean;
}
export interface ModifierGroupResponse {
  id: UUID; companyId: UUID; name: string; minSelect: number; maxSelect: number;
  required: boolean; modifiers: ModifierResponse[];
}
export interface CreateModifierGroupRequest { name: string; minSelect: number; maxSelect: number; }
export interface UpdateModifierGroupRequest { name: string; minSelect: number; maxSelect: number; }
export interface UpdateModifierRequest {
  name: string; priceDelta: string; active: boolean; displayOrder: number; recipeDeltas?: ConsumptionLine[];
}
export interface AddModifierRequest {
  name: string; priceDelta: string; displayOrder: number; recipeDeltas?: ConsumptionLine[];
}

/* ---- inventory -------------------------------------------------------- */
export interface IngredientResponse {
  id: UUID; companyId: UUID; name: string; baseUnit: string; category?: string; active: boolean; version: number;
}
// version echoes the loaded value on update so the server can reject stale-form edits (409).
export interface IngredientRequest { name: string; baseUnit: string; category?: string; active?: boolean; version?: number; }

export interface SupplierResponse {
  id: UUID; companyId: UUID; name: string; contactPhone?: string; contactEmail?: string;
  address?: string; active: boolean; version: number;
}

// What a supplier sells, mapped to the ingredient it becomes in stock. `unit` is the
// unit it is bought in and must measure the same thing as the ingredient's base unit.
export interface ItemResponse {
  id: UUID; companyId: UUID; sku?: string; name: string; supplierId: UUID; ingredientId: UUID;
  unit: string; active: boolean; version: number;
}
// supplierId/ingredientId are fixed after create — the server ignores them on update.
export interface ItemRequest {
  sku?: string; name: string; supplierId: UUID; ingredientId: UUID; unit: string;
  active?: boolean; version?: number;
}
export interface SupplierRequest {
  name: string; contactPhone?: string; contactEmail?: string; address?: string; active?: boolean; version?: number;
}

export interface StockItemResponse {
  ingredientId: UUID; branchId: UUID; quantityOnHand: string; reorderLevel: string;
  avgUnitCost: Money; belowReorderLevel: boolean;
}
/** Mirrors the backend `MovementType` enum. */
export type MovementType =
  'PURCHASE_RECEIPT' | 'SALE_DEPLETION' | 'SALE_REVERSAL' | 'WASTE' | 'ADJUSTMENT' | 'TRANSFER_IN' | 'TRANSFER_OUT';
export interface MovementResponse {
  id: UUID; ingredientId: UUID; branchId: UUID; type: MovementType; quantity: string;
  unitCost?: Money; refType?: string; refId?: UUID; note?: string; occurredAt: string; actor: string;
}
export interface AdjustRequest { ingredientId: UUID; quantityDelta: string; unit?: string; note?: string; }
export interface WasteRequest { ingredientId: UUID; quantity: string; unit?: string; note?: string; }
export interface ReorderLevelRequest { ingredientId: UUID; reorderLevel: string; }

export type PoStatus = 'DRAFT' | 'SENT' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CANCELLED';
export interface PoLineResponse {
  id: UUID; itemId?: UUID; ingredientId: UUID; unit: string; orderedQty: string; receivedQty: string;
  unitCost: Money;
}
export interface PurchaseOrderResponse {
  id: UUID; branchId: UUID; supplierId: UUID; status: string; note?: string; lines: PoLineResponse[];
}
// A line is an item (unit comes from it) or an ingredient + unit — never both. Qty and cost are per `unit`.
export interface PoLineRequest { itemId?: UUID; ingredientId?: UUID; unit?: string; orderedQty: string; unitCost: string; }
export interface CreatePurchaseOrderRequest { supplierId: UUID; note?: string; lines: PoLineRequest[]; }
export interface ReceiptRequest { lineId: UUID; receivedQty: string; unitCostOverride?: string | null; }
export interface ReceiveRequest { receipts: ReceiptRequest[]; }

/* ---- sales / POS ------------------------------------------------------ */
export type OrderType = 'DINE_IN' | 'TAKEAWAY';
export type PaymentMethod = 'CASH' | 'CARD' | 'EWALLET';
export type OrderStatus = 'OPEN' | 'PAID' | 'COMPLETED' | 'CANCELLED' | 'VOID' | 'REFUNDED';

export interface LineRequest { productId: UUID; quantity: number; modifierIds?: UUID[]; lineDiscount?: string; }
export interface CreateOrderRequest {
  orderType: OrderType; orderDiscount?: string; lines: LineRequest[];
  /** The till's id for this attempt; a repeat of the same id returns the first order instead of a second. */
  clientRequestId?: UUID;
}

/** The branch menu the till sells from: effectivePrice is what the branch charges, modifiers are the active ones. */
export interface MenuModifier { id: UUID; name: string; priceDelta: Money; }
export interface MenuModifierGroup { id: UUID; name: string; minSelect: number; maxSelect: number; modifiers: MenuModifier[]; }
export interface MenuProduct {
  id: UUID; categoryId: UUID; name: string; sku: string; effectivePrice: Money; modifierGroups: MenuModifierGroup[];
  /** False when the branch cannot make one more with the stock on hand (modifiers are not considered here). */
  available: boolean;
}

/** An ingredient the cart would take below zero: it needs `required` and the branch has `onHand`, both in `unit`. */
export interface StockShortfall { ingredientId: UUID; ingredientName: string; unit: string; required: number; onHand: number; }

export interface PaymentRequest { method: PaymentMethod; amount: string; }

export interface OrderModifierResponse { modifierId: UUID; name: string; priceDelta: Money; }
export interface LineResponse {
  id: UUID; productId: UUID; productName: string; unitPrice: Money; quantity: number;
  lineDiscount: Money; lineTotal: Money; modifiers: OrderModifierResponse[];
}
export interface PaymentResponse { method: string; type: string; amount: Money; at: string; }
export interface OrderResponse {
  id: UUID; branchId: UUID; cashierUserId: UUID; orderType: string; status: OrderStatus;
  currency: string; taxRate: string; subtotal: string; discountTotal: string; taxTotal: string;
  grandTotal: string; cogsTotal: string; amountPaid: string;
  lines: LineResponse[]; payments: PaymentResponse[]; createdAt: string;
  /** Only on a quote: what the cart is short of, empty when it fits. */
  stockShortfalls?: StockShortfall[];
}

/* ---- staff (phase 5) --------------------------------------------------- */
export interface EmployeeResponse {
  id: UUID; fullName: string; position?: string; hourlyRate?: Money; // rate and contact only for staff:write
  phone?: string; email?: string; active: boolean; hasPin: boolean;
}
export interface EmployeeRequest {
  fullName: string; position?: string; hourlyRate: string;
  phone?: string; email?: string; active?: boolean;
}
export type ShiftStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export interface ShiftResponse {
  id: UUID; branchId: UUID; employeeId: UUID; employeeName: string; status: ShiftStatus;
  scheduledStart: string; scheduledEnd: string; clockInAt?: string; clockOutAt?: string;
  hourlyRate?: Money; laborCost?: Money; note?: string; employeeHasPin: boolean;
}
export interface AdjustShiftRequest { clockIn?: string; clockOut: string; }
export interface ShiftRequest { employeeId: UUID; scheduledStart: string; scheduledEnd: string; note?: string; }

/* ---- reporting (phase 6) ------------------------------------------------ */
export interface PeriodSummary {
  orderCount: number; grossSales: string; discountTotal: string; netSales: string;
  taxTotal: string; grandTotal: string; cogs: string; laborCost: string;
  grossMargin: string; netMargin: string;
}
export interface DailySales { day: string; orderCount: number; netSales: string; }
export interface BranchPerformance { branchId: UUID; name: string; code: string; summary: PeriodSummary; }
