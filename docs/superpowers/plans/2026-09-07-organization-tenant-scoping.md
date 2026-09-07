# Organization Tenant Scoping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the live cross-tenant read and write gap in the organization module, so company and branch endpoints only ever resolve data belonging to the caller's own company.

**Architecture:** Mirror the Layer 1 scoping pattern already used in inventory, identity, staff, and reporting: company-scoped repository finder methods, a `CurrentUser` lookup in the service, and a 404 (never 403) when the row belongs to another tenant. Branch `companyId` stops being a client-supplied field and is always taken from the authenticated principal, which also closes the branch-move hole in `update`.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Spring Security, Flyway, JUnit 5, MockMvc, Testcontainers PostgreSQL, AssertJ, JsonPath, Angular 18 signals frontend.

**Spec:** `doc/erp-cafe-auth-spec.md` (§9 gap list; this plan adds and closes a gap the spec does not yet track, in the same class as AUTH-G01 through AUTH-G04)

## Global Constraints

- Cross-tenant access returns **404 Not Found**, never 403, so the endpoint does not confirm that another tenant's id exists. This matches `UserService.get` and `EmployeeService.get`.
- Scoping is enforced in the **application layer** (Layer 1). Row-level security (Layer 2) is a separate, later project and is not in scope here.
- Every service method that resolves by id must be scoped. Do not rely on a caller having already scoped.
- Exception to HTTP mapping, per `common/web/GlobalExceptionHandler`: `ResourceNotFoundException` is 404, `BusinessRuleException` is 422, `ConflictException` is 409.
- Integration tests extend `vn.essvn.erpcafe.support.AbstractIntegrationTest` and drive real HTTP through MockMvc with the bootstrap admin token from `adminToken()`.
- Commit subjects use the `[Action] Feature` format. Never add a Co-Authored-By trailer.
- Run tests with `./gradlew test` from `backend/`. Docker must be running for Testcontainers.

---

## File Structure

**Backend, modified:**
- `organization/persistence/CompanyRepository.java` — add a paged self-lookup finder.
- `organization/persistence/BranchRepository.java` — add company-scoped list and by-id finders, plus a branch-existence check for the delete guard.
- `organization/application/CompanyService.java` — scope list, get, update, delete to the caller's company; guard delete against remaining branches.
- `organization/application/BranchService.java` — scope list, get, update, delete; take `companyId` from the principal on create; stop honouring a client-supplied company on update.
- `organization/web/BranchRequest.java` — drop the client-supplied `companyId` field.

**Backend, created:**
- `src/test/java/vn/essvn/erpcafe/organization/OrganizationTenantIsolationTest.java` — the isolation test this module is missing.

**Frontend, modified:**
- `frontend/src/app/core/models.ts` — drop `companyId` from `BranchRequest`.
- `frontend/src/app/features/organization/branches.ts` — remove the company picker and stop sending `companyId`.

---

### Task 1: Scope the company endpoints

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/persistence/CompanyRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/persistence/BranchRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/application/CompanyService.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/organization/OrganizationTenantIsolationTest.java`

**Interfaces:**
- Consumes: `CurrentUser.require().companyId()` from `vn.essvn.erpcafe.identity.security.CurrentUser`; `ResourceNotFoundException.of(String, UUID)`; `BusinessRuleException(String)`.
- Produces: `CompanyRepository.findByIdIn(Collection<UUID>, Pageable) : Page<Company>`; `BranchRepository.existsByCompanyId(UUID) : boolean`; `CompanyService` constructor now takes `(CompanyRepository, BranchRepository, CurrentUser)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/vn/essvn/erpcafe/organization/OrganizationTenantIsolationTest.java`:

```java
package vn.essvn.erpcafe.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Tenant isolation on the organization module's own endpoints.
 *
 * <p>Company and branch reads and writes must resolve only within the caller's
 * company. A second company is seeded directly through the repositories, then
 * observed through HTTP, which must not disclose or mutate it.
 */
class OrganizationTenantIsolationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private CompanyRepository companyRepository;

    protected Company seedForeignCompany() {
        int n = SEQ.incrementAndGet();
        return companyRepository.save(new Company("Foreign %d".formatted(n), "FGN%d".formatted(n)));
    }

    @Test
    void companyList_returnsOnlyTheCallersOwnCompany() throws Exception {
        Company foreign = seedForeignCompany();

        String json = mockMvc.perform(get("/api/v1/companies?size=1000")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.read(json, "$.content[*].code");
        assertThat(codes).doesNotContain(foreign.getCode());
        assertThat(codes).hasSize(1);
    }

    @Test
    void companyGet_rejectsAnotherCompany() throws Exception {
        UUID foreignId = seedForeignCompany().getId();

        mockMvc.perform(get("/api/v1/companies/" + foreignId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void companyDelete_rejectsAnotherCompany() throws Exception {
        Company foreign = seedForeignCompany();

        mockMvc.perform(delete("/api/v1/companies/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());

        assertThat(companyRepository.findById(foreign.getId())).isPresent();
    }
}
```

Add the missing static import for `status()` at the top:

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*OrganizationTenantIsolationTest*'`
Expected: FAIL. `companyList` returns more than one company, `companyGet` returns 200 instead of 404, `companyDelete` returns 204 and soft-deletes the foreign company.

- [ ] **Step 3: Add the scoped repository methods**

In `CompanyRepository.java`, add the import and method:

```java
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

```java
    /** Paged self-lookup: the caller's own company is the only one they may list. */
    Page<Company> findByIdIn(Collection<UUID> ids, Pageable pageable);
```

In `BranchRepository.java`, add:

```java
    boolean existsByCompanyId(UUID companyId);
```

- [ ] **Step 4: Scope CompanyService**

Replace the body of `CompanyService.java` with:

```java
package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.domain.Company;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.organization.web.CompanyRequest;

@Service
@Transactional
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final BranchRepository branchRepository;
    private final CurrentUser currentUser;

    public CompanyService(CompanyRepository companyRepository, BranchRepository branchRepository,
            CurrentUser currentUser) {
        this.companyRepository = companyRepository;
        this.branchRepository = branchRepository;
        this.currentUser = currentUser;
    }

    /** A tenant sees exactly one company: their own. */
    @Transactional(readOnly = true)
    public Page<Company> list(Pageable pageable) {
        return companyRepository.findByIdIn(List.of(currentUser.require().companyId()), pageable);
    }

    /**
     * Resolves a company by id, scoped to the caller's own. Another tenant's id
     * is reported as 404 rather than 403, so the endpoint never confirms it exists.
     */
    @Transactional(readOnly = true)
    public Company get(UUID id) {
        if (!id.equals(currentUser.require().companyId())) {
            throw ResourceNotFoundException.of("Company", id);
        }
        return companyRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id));
    }

    public Company create(CompanyRequest request) {
        if (companyRepository.existsByCode(request.code())) {
            throw new ConflictException("Company code already exists: " + request.code());
        }
        return companyRepository.save(new Company(request.name(), request.code()));
    }

    public Company update(UUID id, CompanyRequest request) {
        Company company = get(id);
        if (!company.getCode().equals(request.code()) && companyRepository.existsByCode(request.code())) {
            throw new ConflictException("Company code already exists: " + request.code());
        }
        company.setName(request.name());
        company.setCode(request.code());
        return company;
    }

    /**
     * Deletes the caller's own company. Guarded against orphaning: a company with
     * branches still attached cannot be removed, mirroring the referential-delete
     * guards used in inventory.
     */
    public void delete(UUID id) {
        Company company = get(id);
        if (branchRepository.existsByCompanyId(company.getId())) {
            throw new BusinessRuleException(
                    "Cannot delete a company that still has branches; delete its branches first");
        }
        companyRepository.delete(company);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*OrganizationTenantIsolationTest*'`
Expected: PASS, all three company tests green.

- [ ] **Step 6: Run the full suite for regressions**

Run: `cd backend && ./gradlew test`
Expected: PASS. `AuthFlowIntegrationTest.admin_canListBranches` still passes because the bootstrap admin's company owns the seeded HQ branch.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/vn/essvn/erpcafe/organization backend/src/test/java/vn/essvn/erpcafe/organization
git commit -m "[Fix] Scope company reads and writes to the caller's company"
```

---

### Task 2: Scope the branch endpoints and stop trusting client-supplied company

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/persistence/BranchRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/application/BranchService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/organization/web/BranchRequest.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/organization/OrganizationTenantIsolationTest.java`

**Interfaces:**
- Consumes: `CompanyRepository.findByIdIn`, `BranchRepository.existsByCompanyId` from Task 1; `CurrentUser.require().companyId()`.
- Produces: `BranchRepository.findByCompanyId(UUID, Pageable) : Page<Branch>`; `BranchRepository.findByIdAndCompanyId(UUID, UUID) : Optional<Branch>`; `BranchRequest` record without a `companyId` component, so its canonical constructor becomes `(String name, String code, String address, String phone, Boolean active)`.

- [ ] **Step 1: Write the failing tests**

Append these methods to `OrganizationTenantIsolationTest`, and add the imports they need:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.springframework.http.MediaType;

import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
```

```java
    @Autowired
    private BranchRepository branchRepository;

    private Branch seedForeignBranch() {
        Company foreign = seedForeignCompany();
        return branchRepository.save(new Branch(foreign.getId(), "Foreign Outlet", "FOUT"));
    }

    @Test
    void branchList_doesNotLeakBranchesFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        String json = mockMvc.perform(get("/api/v1/branches?size=1000")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(json, "$.content[*].id");
        assertThat(ids).doesNotContain(foreign.getId().toString());
    }

    @Test
    void branchGet_rejectsBranchFromAnotherCompany() throws Exception {
        UUID foreignId = seedForeignBranch().getId();

        mockMvc.perform(get("/api/v1/branches/" + foreignId)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void branchUpdate_rejectsBranchFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        mockMvc.perform(put("/api/v1/branches/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\",\"code\":\"HIJ\"}"))
                .andExpect(status().isNotFound());

        assertThat(branchRepository.findById(foreign.getId()).orElseThrow().getName())
                .isEqualTo("Foreign Outlet");
    }

    @Test
    void branchDelete_rejectsBranchFromAnotherCompany() throws Exception {
        Branch foreign = seedForeignBranch();

        mockMvc.perform(delete("/api/v1/branches/" + foreign.getId())
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());

        assertThat(branchRepository.findById(foreign.getId())).isPresent();
    }

    @Test
    void branchCreate_assignsTheCallersOwnCompany() throws Exception {
        String json = mockMvc.perform(post("/api/v1/branches")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Outlet\",\"code\":\"NEW1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String callerCompanyId = JsonPath.read(
                mockMvc.perform(get("/api/v1/auth/me")
                                .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                        .andReturn().getResponse().getContentAsString(),
                "$.companyId");

        assertThat(JsonPath.read(json, "$.companyId").toString()).isEqualTo(callerCompanyId);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*OrganizationTenantIsolationTest*'`
Expected: FAIL. The list leaks the foreign branch, get/update/delete return 200 rather than 404, and create fails to compile the payload without `companyId` because the field is still `@NotNull`.

- [ ] **Step 3: Add the scoped branch finders**

In `BranchRepository.java`, add the imports and methods:

```java
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

```java
    Page<Branch> findByCompanyId(UUID companyId, Pageable pageable);

    Optional<Branch> findByIdAndCompanyId(UUID id, UUID companyId);
```

- [ ] **Step 4: Drop companyId from the request payload**

Replace `BranchRequest.java` with:

```java
package vn.essvn.erpcafe.organization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a branch. The owning company is never client-supplied:
 * it is taken from the authenticated principal, so a caller cannot create a branch
 * in, or move a branch to, another tenant.
 */
public record BranchRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 32) String code,
        @Size(max = 500) String address,
        @Size(max = 32) String phone,
        Boolean active) {
}
```

- [ ] **Step 5: Scope BranchService**

Replace the CRUD half of `BranchService.java` so the class reads:

```java
package vn.essvn.erpcafe.organization.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;
import vn.essvn.erpcafe.organization.domain.Branch;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;
import vn.essvn.erpcafe.organization.persistence.BranchRepository;
import vn.essvn.erpcafe.organization.web.BranchRequest;

@Service
@Transactional
public class BranchService implements OrganizationApi {

    private final BranchRepository branchRepository;
    private final BranchMapper branchMapper;
    private final CurrentUser currentUser;

    public BranchService(BranchRepository branchRepository, BranchMapper branchMapper,
            CurrentUser currentUser) {
        this.branchRepository = branchRepository;
        this.branchMapper = branchMapper;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public Page<Branch> list(Pageable pageable) {
        return branchRepository.findByCompanyId(currentUser.require().companyId(), pageable);
    }

    /** Resolves a branch within the caller's company; another tenant's id is a 404. */
    @Transactional(readOnly = true)
    public Branch get(UUID id) {
        return branchRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Branch", id));
    }

    public Branch create(BranchRequest request) {
        UUID companyId = currentUser.require().companyId();
        if (branchRepository.existsByCompanyIdAndCode(companyId, request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        Branch branch = new Branch(companyId, request.name(), request.code());
        apply(branch, request);
        return branchRepository.save(branch);
    }

    /**
     * Updates a branch in the caller's company. The owning company is deliberately
     * not touched: moving a branch between tenants would hand every branch-scoped
     * query downstream to the wrong company.
     */
    public Branch update(UUID id, BranchRequest request) {
        Branch branch = get(id);
        if (!branch.getCode().equals(request.code())
                && branchRepository.existsByCompanyIdAndCode(branch.getCompanyId(), request.code())) {
            throw new ConflictException("Branch code already exists in company: " + request.code());
        }
        branch.setCode(request.code());
        apply(branch, request);
        return branch;
    }

    public void delete(UUID id) {
        branchRepository.delete(get(id));
    }

    // --- OrganizationApi (published to other modules) ---

    @Override
    @Transactional(readOnly = true)
    public Optional<BranchDto> findBranch(UUID branchId) {
        return branchRepository.findById(branchId).map(branchMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExists(UUID branchId) {
        return branchRepository.findById(branchId).map(Branch::isActive).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean branchExistsInCompany(UUID branchId, UUID companyId) {
        return branchRepository.existsByIdAndCompanyIdAndActiveTrue(branchId, companyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BranchDto> listActiveBranches(UUID companyId) {
        return branchRepository.findByCompanyIdAndActiveTrue(companyId).stream()
                .map(branchMapper::toDto)
                .toList();
    }

    private void apply(Branch branch, BranchRequest request) {
        branch.setName(request.name());
        branch.setAddress(request.address());
        branch.setPhone(request.phone());
        if (request.active() != null) {
            branch.setActive(request.active());
        }
    }
}
```

Note that `CompanyRepository` and the `requireCompany` helper are no longer needed here: the principal's company is guaranteed to exist.

The published `OrganizationApi` methods keep taking an explicit `companyId` and are deliberately left unscoped by `CurrentUser`. They are called by other modules that already pass the caller's company, and by `DataInitializer`, which runs with no security context.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*OrganizationTenantIsolationTest*'`
Expected: PASS, all eight tests green.

- [ ] **Step 7: Run the full suite**

Run: `cd backend && ./gradlew test`
Expected: PASS. If `AuthFlowIntegrationTest.createBranch_recordsAuthenticatedUserAsCreatedBy` posts a `companyId`, an unknown JSON field is ignored by default, so it still passes. If it asserts on a company other than the admin's, update the assertion to the admin's own company.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/vn/essvn/erpcafe/organization backend/src/test/java/vn/essvn/erpcafe/organization
git commit -m "[Fix] Scope branch reads and writes to the caller's company"
```

---

### Task 3: Align the frontend with the narrowed branch payload

**Files:**
- Modify: `frontend/src/app/core/models.ts`
- Modify: `frontend/src/app/features/organization/branches.ts`

**Interfaces:**
- Consumes: the `BranchRequest` shape from Task 2, now without `companyId`.
- Produces: TypeScript `BranchRequest` as `{ name: string; code: string; address?: string; phone?: string; active?: boolean }`.

- [ ] **Step 1: Narrow the TypeScript request type**

In `frontend/src/app/core/models.ts`, replace the `BranchRequest` interface with:

```typescript
export interface BranchRequest {
  name: string; code: string;
  address?: string; phone?: string; active?: boolean;
}
```

- [ ] **Step 2: Remove the company picker from the branch form**

In `frontend/src/app/features/organization/branches.ts`:
- Delete the `<select id="br-company">` control and its surrounding form field wrapper from the template.
- Change `blank()` to return `{ name: '', code: '', address: '', phone: '', active: true }`.
- In `edit(b)`, drop `companyId: b.companyId` from the assigned form object.
- In the submit guard, change the condition to `if (!this.form.name || !this.form.code)` and the message to `'Name and code are required'`.
- Delete the line that defaults `form.companyId` when exactly one company is loaded.
- If `companies()` and the `CompanyResponse` import become unused after this, remove them too.

- [ ] **Step 3: Verify the frontend compiles**

Run: `cd frontend && npx tsc --noEmit -p tsconfig.app.json`
Expected: no errors. If the project has no such script, run `npm run build` instead.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/core/models.ts frontend/src/app/features/organization/branches.ts
git commit -m "[Refactor] Drop client-supplied company from the branch form"
```

---

## Deferred, deliberately out of scope

- **Company creation is still tenant-facing.** A company created through `POST /api/v1/companies` has no roles, users, or branches, so it is orphaned. Whether tenant provisioning should become a platform-only concern is a product decision, tracked separately.
- **Row-level security on `branch` and `company`.** ADR-0003 commits to this as the Layer 2 backstop. It needs the non-superuser application role to bite, and belongs with the wider RLS rollout.
- **Company-wide grants.** A newly created branch has no users with access to it. Fixing that means a nullable `branch_id` on `user_branch_access`, which is a prerequisite for per-branch permission evaluation (AUTH-G07) and is its own project.
