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
  [key: string]: unknown;
}

/* ---- identity / auth -------------------------------------------------- */
export interface LoginRequest { email: string; password: string; }
export interface TokenResponse { accessToken: string; refreshToken: string; tokenType: string; }
export interface MeResponse {
  userId: UUID;
  email: string;
  companyId: UUID;
  branchIds: UUID[];
  roles: string[];
  permissions: string[];
}

export type UserStatus = 'ACTIVE' | 'DISABLED' | 'LOCKED';
export interface UserResponse {
  id: UUID; companyId: UUID; email: string; fullName: string;
  status: UserStatus; createdAt: string; createdBy: string;
}
export interface CreateUserRequest { email: string; password: string; fullName?: string; }
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
  createdAt: string; createdBy: string; updatedAt: string; updatedBy: string;
}
export interface BranchRequest {
  companyId: UUID; name: string; code: string;
  address?: string; phone?: string; active?: boolean;
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
export interface RecipeResponse { productId: UUID; lines: ConsumptionLine[]; }
export interface SetRecipeRequest { lines: ConsumptionLine[]; }

export interface ModifierResponse {
  id: UUID; name: string; priceDelta: Money; displayOrder: number; active: boolean;
}
export interface ModifierGroupResponse {
  id: UUID; companyId: UUID; name: string; minSelect: number; maxSelect: number;
  required: boolean; modifiers: ModifierResponse[];
}
export interface CreateModifierGroupRequest { name: string; minSelect: number; maxSelect: number; }
export interface AddModifierRequest {
  name: string; priceDelta: string; displayOrder: number; recipeDeltas?: ConsumptionLine[];
}

/* ---- inventory -------------------------------------------------------- */
export interface IngredientResponse {
  id: UUID; companyId: UUID; name: string; baseUnit: string; category?: string; active: boolean;
}
export interface IngredientRequest { name: string; baseUnit: string; category?: string; active?: boolean; }

export interface SupplierResponse {
  id: UUID; companyId: UUID; name: string; contactPhone?: string; contactEmail?: string;
  address?: string; active: boolean;
}
export interface SupplierRequest {
  name: string; contactPhone?: string; contactEmail?: string; address?: string; active?: boolean;
}

export interface StockItemResponse {
  ingredientId: UUID; branchId: UUID; quantityOnHand: string; reorderLevel: string;
  avgUnitCost: Money; belowReorderLevel: boolean;
}
export interface MovementResponse {
  id: UUID; ingredientId: UUID; branchId: UUID; type: string; quantity: string;
  unitCost?: Money; refType?: string; refId?: UUID; note?: string; occurredAt: string; actor: string;
}
export interface AdjustRequest { ingredientId: UUID; quantityDelta: string; note?: string; }
export interface WasteRequest { ingredientId: UUID; quantity: string; note?: string; }
export interface ReorderLevelRequest { ingredientId: UUID; reorderLevel: string; }

export type PoStatus = 'DRAFT' | 'SENT' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CANCELLED';
export interface PoLineResponse {
  id: UUID; ingredientId: UUID; orderedQty: string; receivedQty: string; unitCost: Money;
}
export interface PurchaseOrderResponse {
  id: UUID; branchId: UUID; supplierId: UUID; status: string; note?: string; lines: PoLineResponse[];
}
export interface PoLineRequest { ingredientId: UUID; orderedQty: string; unitCost: string; }
export interface CreatePurchaseOrderRequest { supplierId: UUID; note?: string; lines: PoLineRequest[]; }
export interface ReceiptRequest { lineId: UUID; receivedQty: string; unitCostOverride?: string | null; }
export interface ReceiveRequest { receipts: ReceiptRequest[]; }

/* ---- sales / POS ------------------------------------------------------ */
export type OrderType = 'DINE_IN' | 'TAKEAWAY';
export type PaymentMethod = 'CASH' | 'CARD' | 'EWALLET';
export type OrderStatus = 'OPEN' | 'PAID' | 'COMPLETED' | 'CANCELLED' | 'VOID' | 'REFUNDED';

export interface LineRequest { productId: UUID; quantity: number; modifierIds?: UUID[]; lineDiscount?: string; }
export interface CreateOrderRequest {
  orderType: OrderType; taxRate?: string; orderDiscount?: string; lines: LineRequest[];
}
export interface PaymentRequest { method: PaymentMethod; amount: string; }
export interface RefundRequest { method?: PaymentMethod; }

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
}

/* ---- staff (phase 5) --------------------------------------------------- */
export interface EmployeeResponse {
  id: UUID; fullName: string; position?: string; hourlyRate: Money;
  phone?: string; email?: string; active: boolean;
}
export interface EmployeeRequest {
  fullName: string; position?: string; hourlyRate: string;
  phone?: string; email?: string; active?: boolean;
}
export type ShiftStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export interface ShiftResponse {
  id: UUID; branchId: UUID; employeeId: UUID; employeeName: string; status: ShiftStatus;
  scheduledStart: string; scheduledEnd: string; clockInAt?: string; clockOutAt?: string;
  hourlyRate?: Money; laborCost?: Money; note?: string;
}
export interface ShiftRequest { employeeId: UUID; scheduledStart: string; scheduledEnd: string; note?: string; }

/* ---- reporting (phase 6) ------------------------------------------------ */
export interface PeriodSummary {
  orderCount: number; grossSales: string; discountTotal: string; taxTotal: string;
  revenue: string; cogsOrders: string; cogsDepletion: string; laborCost: string;
  grossMargin: string; netMargin: string;
}
export interface DailySales { day: string; orderCount: number; revenue: string; }
export interface BranchPerformance { branchId: UUID; name: string; code: string; summary: PeriodSummary; }
