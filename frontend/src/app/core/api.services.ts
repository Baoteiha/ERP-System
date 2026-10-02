import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import * as M from './models';
import { SUPPRESS_ERROR_TOAST } from './http.interceptors';

const V1 = '/api/v1';

function pageParams(page = 0, size = 20, sort?: string): HttpParams {
  let p = new HttpParams().set('page', page).set('size', size);
  if (sort) p = p.set('sort', sort);
  return p;
}

/* ---- organization (companies + branches) ------------------------------ */
@Injectable({ providedIn: 'root' })
export class OrganizationService {
  private http = inject(HttpClient);

  listCompanies(page = 0, size = 50) {
    return this.http.get<M.PageResponse<M.CompanyResponse>>(`${V1}/companies`, { params: pageParams(page, size) });
  }
  updateCompany(id: string, body: M.CompanyRequest) { return this.http.put<M.CompanyResponse>(`${V1}/companies/${id}`, body); }
  deleteCompany(id: string) { return this.http.delete<void>(`${V1}/companies/${id}`); }

  listBranches(page = 0, size = 100, quiet = false) {
    return this.http.get<M.PageResponse<M.BranchResponse>>(`${V1}/branches`, {
      params: pageParams(page, size),
      context: new HttpContext().set(SUPPRESS_ERROR_TOAST, quiet),
    });
  }
  createBranch(body: M.BranchRequest) { return this.http.post<M.BranchResponse>(`${V1}/branches`, body); }
  updateBranch(id: string, body: M.BranchRequest) { return this.http.put<M.BranchResponse>(`${V1}/branches/${id}`, body); }
  deleteBranch(id: string) { return this.http.delete<void>(`${V1}/branches/${id}`); }
}

/* ---- identity (users + roles) ----------------------------------------- */
@Injectable({ providedIn: 'root' })
export class IdentityService {
  private http = inject(HttpClient);

  listUsers(page = 0, size = 50) {
    return this.http.get<M.PageResponse<M.UserResponse>>(`${V1}/users`, { params: pageParams(page, size) });
  }
  createUser(body: M.CreateUserRequest) { return this.http.post<M.UserResponse>(`${V1}/users`, body); }
  getUser(id: string) { return this.http.get<M.UserResponse>(`${V1}/users/${id}`); }
  updateUser(id: string, body: M.UpdateUserRequest) { return this.http.put<M.UserResponse>(`${V1}/users/${id}`, body); }
  /** Disabling an account also revokes its refresh tokens. */
  changeUserStatus(id: string, body: M.UpdateStatusRequest) { return this.http.put<M.UserResponse>(`${V1}/users/${id}/status`, body); }
  changeOwnPassword(body: M.ChangePasswordRequest) { return this.http.post<void>(`${V1}/users/me/password`, body); }
  resetUserPassword(id: string, body: M.ResetPasswordRequest) { return this.http.put<void>(`${V1}/users/${id}/password`, body); }
  /** Signed out; answers 202 whether or not the email has an account. The page reports errors itself. */
  requestPasswordReset(email: string) {
    return this.http.post<void>(`${V1}/auth/password-reset/request`, { email },
      { context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) });
  }
  confirmPasswordReset(body: M.PasswordResetConfirmRequest) {
    return this.http.post<void>(`${V1}/auth/password-reset/confirm`, body,
      { context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true) });
  }
  userAccess(id: string) { return this.http.get<M.BranchAccessResponse[]>(`${V1}/users/${id}/access`); }
  grantAccess(id: string, body: M.GrantAccessRequest) { return this.http.post<M.BranchAccessResponse>(`${V1}/users/${id}/access`, body); }
  revokeAccess(id: string, branchId: string) { return this.http.delete<void>(`${V1}/users/${id}/access/${branchId}`); }

  listRoles() { return this.http.get<M.RoleResponse[]>(`${V1}/roles`); }
  createRole(body: M.CreateRoleRequest) { return this.http.post<M.RoleResponse>(`${V1}/roles`, body); }
  replacePermissions(id: string, body: M.PermissionsRequest) { return this.http.put<M.RoleResponse>(`${V1}/roles/${id}/permissions`, body); }
  addPermissions(id: string, body: M.PermissionsRequest) { return this.http.post<M.RoleResponse>(`${V1}/roles/${id}/permissions`, body); }
  removePermission(id: string, perm: string) { return this.http.delete<M.RoleResponse>(`${V1}/roles/${id}/permissions/${perm}`); }
}

/* ---- catalog ---------------------------------------------------------- */
@Injectable({ providedIn: 'root' })
export class CatalogService {
  private http = inject(HttpClient);

  listCategories() { return this.http.get<M.CategoryResponse[]>(`${V1}/categories`); }
  createCategory(b: M.CategoryRequest) { return this.http.post<M.CategoryResponse>(`${V1}/categories`, b); }
  updateCategory(id: string, b: M.CategoryRequest) { return this.http.put<M.CategoryResponse>(`${V1}/categories/${id}`, b); }
  deleteCategory(id: string) { return this.http.delete<void>(`${V1}/categories/${id}`); }

  listProducts(page = 0, size = 100) {
    return this.http.get<M.PageResponse<M.ProductResponse>>(`${V1}/products`, { params: pageParams(page, size) });
  }
  menu() { return this.http.get<M.MenuProduct[]>(`${V1}/menu`); }
  getProduct(id: string) { return this.http.get<M.ProductResponse>(`${V1}/products/${id}`); }
  createProduct(b: M.CreateProductRequest) { return this.http.post<M.ProductResponse>(`${V1}/products`, b); }
  updateProduct(id: string, b: M.UpdateProductRequest) { return this.http.put<M.ProductResponse>(`${V1}/products/${id}`, b); }
  deleteProduct(id: string) { return this.http.delete<void>(`${V1}/products/${id}`); }
  setModifierGroups(id: string, b: M.SetModifierGroupsRequest) { return this.http.put<M.ProductResponse>(`${V1}/products/${id}/modifier-groups`, b); }
  availability(id: string) { return this.http.get<M.BranchAvailabilityResponse[]>(`${V1}/products/${id}/availability`); }
  setAvailability(id: string, b: M.BranchAvailabilityRequest) { return this.http.put<M.BranchAvailabilityResponse>(`${V1}/products/${id}/availability`, b); }
  price(id: string, branchId?: string) {
    let params = new HttpParams();
    if (branchId) params = params.set('branchId', branchId);
    return this.http.get<M.PriceResponse>(`${V1}/products/${id}/price`, { params });
  }

  getRecipe(productId: string) {
    return this.http.get<M.RecipeResponse>(`${V1}/products/${productId}/recipe`, {
      context: new HttpContext().set(SUPPRESS_ERROR_TOAST, true),
    });
  }
  setRecipe(productId: string, b: M.SetRecipeRequest) { return this.http.put<M.RecipeResponse>(`${V1}/products/${productId}/recipe`, b); }

  listModifierGroups() { return this.http.get<M.ModifierGroupResponse[]>(`${V1}/modifier-groups`); }
  createModifierGroup(b: M.CreateModifierGroupRequest) { return this.http.post<M.ModifierGroupResponse>(`${V1}/modifier-groups`, b); }
  addModifier(groupId: string, b: M.AddModifierRequest) { return this.http.post<M.ModifierResponse>(`${V1}/modifier-groups/${groupId}/modifiers`, b); }
  updateModifierGroup(id: string, b: M.UpdateModifierGroupRequest) { return this.http.put<M.ModifierGroupResponse>(`${V1}/modifier-groups/${id}`, b); }
  updateModifier(groupId: string, id: string, b: M.UpdateModifierRequest) { return this.http.put<M.ModifierResponse>(`${V1}/modifier-groups/${groupId}/modifiers/${id}`, b); }
  removeModifier(groupId: string, id: string) { return this.http.delete<void>(`${V1}/modifier-groups/${groupId}/modifiers/${id}`); }
  deleteModifierGroup(id: string) { return this.http.delete<void>(`${V1}/modifier-groups/${id}`); }
}

/* ---- inventory + purchasing ------------------------------------------- */
@Injectable({ providedIn: 'root' })
export class InventoryService {
  private http = inject(HttpClient);

  listIngredients() { return this.http.get<M.IngredientResponse[]>(`${V1}/ingredients`); }
  createIngredient(b: M.IngredientRequest) { return this.http.post<M.IngredientResponse>(`${V1}/ingredients`, b); }
  updateIngredient(id: string, b: M.IngredientRequest) { return this.http.put<M.IngredientResponse>(`${V1}/ingredients/${id}`, b); }
  deleteIngredient(id: string) { return this.http.delete<void>(`${V1}/ingredients/${id}`); }

  listSuppliers() { return this.http.get<M.SupplierResponse[]>(`${V1}/suppliers`); }
  createSupplier(b: M.SupplierRequest) { return this.http.post<M.SupplierResponse>(`${V1}/suppliers`, b); }
  updateSupplier(id: string, b: M.SupplierRequest) { return this.http.put<M.SupplierResponse>(`${V1}/suppliers/${id}`, b); }
  deleteSupplier(id: string) { return this.http.delete<void>(`${V1}/suppliers/${id}`); }

  listItems(filter?: { supplierId?: string; ingredientId?: string }) {
    let params = new HttpParams();
    if (filter?.supplierId) params = params.set('supplierId', filter.supplierId);
    if (filter?.ingredientId) params = params.set('ingredientId', filter.ingredientId);
    return this.http.get<M.ItemResponse[]>(`${V1}/items`, { params });
  }
  createItem(b: M.ItemRequest) { return this.http.post<M.ItemResponse>(`${V1}/items`, b); }
  updateItem(id: string, b: M.ItemRequest) { return this.http.put<M.ItemResponse>(`${V1}/items/${id}`, b); }
  deleteItem(id: string) { return this.http.delete<void>(`${V1}/items/${id}`); }

  listStock() { return this.http.get<M.StockItemResponse[]>(`${V1}/stock`); }
  lowStock() { return this.http.get<M.StockItemResponse[]>(`${V1}/stock/low`); }
  movements(ingredientId?: string, page = 0, size = 30): Observable<M.PageResponse<M.MovementResponse>> {
    let params = pageParams(page, size);
    if (ingredientId) params = params.set('ingredientId', ingredientId);
    return this.http.get<M.PageResponse<M.MovementResponse>>(`${V1}/stock/movements`, { params });
  }
  adjust(b: M.AdjustRequest) { return this.http.post<M.StockItemResponse>(`${V1}/stock/adjust`, b); }
  waste(b: M.WasteRequest) { return this.http.post<M.StockItemResponse>(`${V1}/stock/waste`, b); }
  setReorderLevel(b: M.ReorderLevelRequest) { return this.http.put<M.StockItemResponse>(`${V1}/stock/reorder-level`, b); }

  listPurchaseOrders(page = 0, size = 50) {
    return this.http.get<M.PageResponse<M.PurchaseOrderResponse>>(`${V1}/purchase-orders`, { params: pageParams(page, size) });
  }
  getPurchaseOrder(id: string) { return this.http.get<M.PurchaseOrderResponse>(`${V1}/purchase-orders/${id}`); }
  createPurchaseOrder(b: M.CreatePurchaseOrderRequest) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders`, b); }
  sendPurchaseOrder(id: string) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders/${id}/send`, {}); }
  receivePurchaseOrder(id: string, b: M.ReceiveRequest) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders/${id}/receive`, b); }
  cancelPurchaseOrder(id: string) { return this.http.post<M.PurchaseOrderResponse>(`${V1}/purchase-orders/${id}/cancel`, {}); }
}

/* ---- sales / POS ------------------------------------------------------ */
@Injectable({ providedIn: 'root' })
export class SalesService {
  private http = inject(HttpClient);

  listOrders(page = 0, size = 25) {
    return this.http.get<M.PageResponse<M.OrderResponse>>(`${V1}/orders`, { params: pageParams(page, size) });
  }
  getOrder(id: string) { return this.http.get<M.OrderResponse>(`${V1}/orders/${id}`); }
  createOrder(b: M.CreateOrderRequest) { return this.http.post<M.OrderResponse>(`${V1}/orders`, b); }
  quote(b: M.CreateOrderRequest) { return this.http.post<M.OrderResponse>(`${V1}/orders/quote`, b); }
  addPayment(id: string, b: M.PaymentRequest) { return this.http.post<M.OrderResponse>(`${V1}/orders/${id}/payments`, b); }
  complete(id: string) { return this.http.post<M.OrderResponse>(`${V1}/orders/${id}/complete`, {}); }
  /** `quiet` is for background clean-up the cashier never asked for: its failure must not toast. */
  cancel(id: string, quiet = false) {
    return this.http.post<M.OrderResponse>(`${V1}/orders/${id}/cancel`, {}, {
      context: new HttpContext().set(SUPPRESS_ERROR_TOAST, quiet),
    });
  }
  void(id: string) { return this.http.post<M.OrderResponse>(`${V1}/orders/${id}/void`, {}); }
  refund(id: string) { return this.http.post<M.OrderResponse>(`${V1}/orders/${id}/refund`, {}); }
}

/* ---- staff (employees + shifts) ---------------------------------------- */
@Injectable({ providedIn: 'root' })
export class StaffService {
  private http = inject(HttpClient);

  listEmployees() { return this.http.get<M.EmployeeResponse[]>(`${V1}/staff/employees`); }
  createEmployee(b: M.EmployeeRequest) { return this.http.post<M.EmployeeResponse>(`${V1}/staff/employees`, b); }
  updateEmployee(id: string, b: M.EmployeeRequest) { return this.http.put<M.EmployeeResponse>(`${V1}/staff/employees/${id}`, b); }

  setEmployeePin(id: string, pin: string) { return this.http.put<void>(`${V1}/staff/employees/${id}/pin`, { pin }); }

  listShifts(from: string, to: string) {
    return this.http.get<M.ShiftResponse[]>(`${V1}/staff/shifts`, { params: new HttpParams().set('from', from).set('to', to) });
  }
  scheduleShift(b: M.ShiftRequest) { return this.http.post<M.ShiftResponse>(`${V1}/staff/shifts`, b); }
  rescheduleShift(id: string, b: Omit<M.ShiftRequest, 'employeeId'>) { return this.http.put<M.ShiftResponse>(`${V1}/staff/shifts/${id}`, b); }
  openShifts() { return this.http.get<M.ShiftResponse[]>(`${V1}/staff/shifts/open`); }
  adjustShift(id: string, b: M.AdjustShiftRequest) { return this.http.post<M.ShiftResponse>(`${V1}/staff/shifts/${id}/adjust`, b); }
  clockIn(id: string, pin?: string) { return this.http.post<M.ShiftResponse>(`${V1}/staff/shifts/${id}/clock-in`, pin ? { pin } : {}); }
  clockOut(id: string, pin?: string) { return this.http.post<M.ShiftResponse>(`${V1}/staff/shifts/${id}/clock-out`, pin ? { pin } : {}); }
  cancelShift(id: string) { return this.http.post<M.ShiftResponse>(`${V1}/staff/shifts/${id}/cancel`, {}); }
}

/* ---- reporting ---------------------------------------------------------- */
@Injectable({ providedIn: 'root' })
export class ReportingService {
  private http = inject(HttpClient);

  private range(from?: string, to?: string) {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return params;
  }

  /** No range means today in the reporting zone (the server decides). */
  summary(from?: string, to?: string) {
    return this.http.get<M.PeriodSummary>(`${V1}/reports/summary`, { params: this.range(from, to) });
  }
  salesByDay(from: string, to: string) {
    return this.http.get<M.DailySales[]>(`${V1}/reports/sales-by-day`, { params: this.range(from, to) });
  }
  outlets(from: string, to: string) {
    return this.http.get<M.BranchPerformance[]>(`${V1}/reports/outlets`, { params: this.range(from, to) });
  }
}
