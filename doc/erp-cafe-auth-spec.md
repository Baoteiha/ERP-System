# ERP Cafe — Authentication & Authorization Spec

**Status:** as-built, describing the system at commit `ae264e6`.

This documents what exists today, ahead of a possible migration to an external
identity provider. It is a description, not a proposal: where the code and its
own javadoc disagree, this file follows the code.

## 1. Goal

Establish who a request is from (**authentication**) and what they may do
(**authorization**), across a multi-tenant deployment where one instance serves
many café companies, each with several branches.

Three questions have to be answered on every request:

1. **Who are you?** — resolved from a signed access token.
2. **What may you do?** — a flat set of permission strings carried in that token.
3. **Where may you do it?** — which company, and which branch of it.

Questions 1 and 2 are solved centrally and consistently. Question 3 is solved
centrally for *branch* and by per-service convention for *company*, which is the
origin of most defects in §9.

## 2. Current state

| Capability | Status |
|---|---|
| Email + password login | ✅ |
| Stateless JWT access tokens (HS256) | ✅ |
| Opaque, hashed, rotating refresh tokens | ✅ |
| Per-branch role grants | ✅ |
| Fine-grained permissions at the endpoint | ✅ |
| Branch header validated against granted access | ✅ |
| Company scoping below the service layer | ⬜ enforced by convention only |
| MFA / passwordless / SSO federation | ⬜ none |
| Password reset, lockout, login rate limiting | ⬜ none |
| Key rotation | ⬜ single static shared secret |
| Login/session audit trail | ⬜ none beyond application logs |

## 3. Domain model

| Entity | Table | Notes |
|---|---|---|
| `User` | `app_user` | `companyId`, `email`, `passwordHash`, `fullName`, `status`. Email is unique **globally**, not per company. Soft-deletable, versioned |
| `UserStatus` | — | `ACTIVE`, `DISABLED` |
| `Role` | `role` | `companyId`, `name`, `description`, and a many-to-many to `Permission` (EAGER). Unique on `(company_id, name)` |
| `Permission` | `permission` | `name` (globally unique), `description`. Reference data synced from code at startup; never created at runtime |
| `UserBranchAccess` | `user_branch_access` | Grants one user one role at one branch. Unique on `(user_id, branch_id)` — **one role per user per branch** |
| `RefreshToken` | `refresh_token` | `userId`, `tokenHash` (SHA-256, unique), `expiresAt`, `revoked` |

The model is **RBAC with a branch dimension**. A user holds a role *per branch*,
so the same person can be MANAGER at one branch and CASHIER at another. The
effective permission set on a token is the **union** across all their grants —
see AUTH-G07 for the consequence.

## 4. Permission catalog

21 permissions, declared in `Permissions.java` and synced into the `permission`
table on startup. This class is the single source of truth for authority strings.

| Domain | Permissions |
|---|---|
| Company | `company:read`, `company:write` |
| Branch | `branch:read`, `branch:write` |
| User | `user:read`, `user:write` |
| Role | `role:read`, `role:write` |
| Catalog | `catalog:read`, `catalog:write` |
| Inventory | `inventory:read`, `inventory:write` |
| Purchasing | `purchasing:read`, `purchasing:write` |
| Sales | `sales:read`, `sales:write`, `sales:refund` |
| Staff | `staff:read`, `staff:write`, `staff:clock` |
| Reporting | `report:read` |

## 5. Default roles

Seeded per company by `DataInitializer`, defined in `DefaultRole.java`.

| Permission | OWNER | MANAGER | CASHIER |
|---|:---:|:---:|:---:|
| `company:read` | ✅ | ✅ | — |
| `company:write` | ✅ | — | — |
| `branch:read` | ✅ | ✅ | ✅ |
| `branch:write` | ✅ | — | — |
| `user:read` | ✅ | ✅ | — |
| `user:write` | ✅ | ✅ | — |
| `role:read` | ✅ | ✅ | — |
| `role:write` | ✅ | — | — |
| `catalog:read` | ✅ | ✅ | ✅ |
| `catalog:write` | ✅ | ✅ | — |
| `inventory:read` | ✅ | ✅ | ✅ |
| `inventory:write` | ✅ | ✅ | — |
| `purchasing:read` | ✅ | ✅ | — |
| `purchasing:write` | ✅ | ✅ | — |
| `sales:read` | ✅ | ✅ | ✅ |
| `sales:write` | ✅ | ✅ | ✅ |
| `sales:refund` | ✅ | ✅ | — |
| `staff:read` | ✅ | ✅ | ✅ |
| `staff:write` | ✅ | ✅ | — |
| `staff:clock` | ✅ | ✅ | ✅ |
| `report:read` | ✅ | ✅ | — |
| **Total** | **21** | **18** | **7** |

The deliberate separations: a cashier may sell but not refund or void, and may
see the schedule and punch in and out but not edit it. A manager may run the
branch but not create branches, edit the company, or define new roles.

Roles are company-scoped rows, so a company may add its own beyond these three.

## 6. Authentication flows

All endpoints under `/api/v1/auth`. Login, refresh, and logout are `permitAll`;
`/me` requires a valid token.

### AUTH-101 — Login

`POST /api/v1/auth/login` with `{email, password}`.

Looks the user up by email, verifies the password with BCrypt, rejects a
`DISABLED` account, and issues a token pair. Failure is a single generic
"Invalid email or password" for both unknown email and wrong password, so the
endpoint does not disclose which emails exist.

### AUTH-102 — Token pair

**Access token** — HS256 JWT, 15-minute TTL, claims:

| Claim | Contents |
|---|---|
| `sub` | user id |
| `email` | login email |
| `companyId` | the user's company |
| `perms` | flat array of permission strings, unioned across all branch grants |
| `roles` | role names |
| `branches` | every branch id the user holds a grant at |

Everything needed to authorize a request is inside the token, so no database
read happens per request.

**Refresh token** — 48 random bytes, hex-encoded, returned to the client once.
Only its SHA-256 hash is persisted, so a database leak yields nothing usable.
30-day TTL.

### AUTH-103 — Refresh with rotation

`POST /api/v1/auth/refresh`. Looks up by hash, rejects expired or revoked,
**revokes the presented token**, and mints a fresh pair. Single-use rotation, so
a stolen token is invalidated the moment the legitimate client next refreshes.

### AUTH-104 — Logout

`POST /api/v1/auth/logout` revokes the presented refresh token. Outstanding
access tokens remain valid until they expire (up to 15 minutes).

### AUTH-105 — Current principal

`GET /api/v1/auth/me` returns the caller's identity, permissions, roles, and
accessible branches, reconstructed from the token.

## 7. Request authorization pipeline

For every request, in order:

1. **`JwtAuthenticationFilter`** (registered before `UsernamePasswordAuthenticationFilter`)
   parses the `Authorization: Bearer` token into an `AuthPrincipal`.
   Invalid or expired → **401** with a problem+json body.
2. **Branch resolution.** If `X-Branch-Id` is present: malformed UUID → **400**;
   a branch not in the principal's granted set → **403**; otherwise it is placed
   in `BranchContext` (a thread-local) for the request.
3. **Authorities** are built as the permission strings verbatim, plus each role
   name prefixed `ROLE_`.
4. **`@PreAuthorize("hasAuthority('x:y')")`** on controller methods enforces the
   permission for that endpoint.
5. **Services** read the tenant from `CurrentUser.require().companyId()` and the
   branch from `BranchContext.require()`, and are individually responsible for
   applying them to their queries.
6. **`finally`** clears both `BranchContext` and the `SecurityContext`.

Step 5 is the weak joint: it is a convention, not a mechanism.

## 8. Configuration

```yaml
app:
  security:
    jwt:
      secret: <shared HS256 key>
      access-token-ttl: 15m
      refresh-token-ttl: 30d
    bootstrap:
      admin-email: <seed admin>
      admin-password: <seed password>
```

Sessions are stateless, CSRF is disabled (token auth, no cookies), and passwords
are hashed with BCrypt at Spring's default strength.

## 9. Known gaps and defects

Ordered by severity. G01–G05 are live in committed code.

| ID | Gap | Severity |
|---|---|---|
| AUTH-G01 | **Branch-grant escalation.** `UserService.grantAccess` validates that the role belongs to the user's company, but `BranchService.branchExists` is unscoped, so a branch id from *another* company passes. The resulting grant lands in the principal's `branches` claim, and `canAccessBranch` then returns true — defeating every branch-scoped query in inventory and staff. Reachable with `user:write`, which MANAGER holds | 🔴 critical |
| AUTH-G02 | **`UserService.list()` is `findAll(pageable)`** — returns every user of every company, with emails and names, to anyone holding `user:read` | 🔴 critical |
| AUTH-G03 | **`UserService.get()` is unscoped `findById`** — cross-company user read | 🔴 high |
| AUTH-G04 | **Company scoping is per-service convention.** Four confirmed IDORs outside identity: `EmployeeService.get`, `ShiftService.get`, `ShiftService.schedule`, `BranchService.listActiveBranches` | 🔴 high |
| AUTH-G05 | **`BranchContextFilter` is a second, unvalidated path** setting `BranchContext` from the same header without the `canAccessBranch` check. Not exploitable as traced (the security chain rejects first), but it should not exist | 🟠 medium |
| AUTH-G06 | **New permissions are backfilled to OWNER only.** `syncOwnerPermissions` reconciles OWNER roles against the catalog; MANAGER and CASHIER are set at creation time and never updated, so existing deployments 403 on newly added endpoints | 🟠 medium |
| AUTH-G07 | **Permissions union across branches.** A user who is MANAGER at branch A and CASHIER at branch B carries the manager permission set at *both*, because `perms` is a flat union and is not re-evaluated per active branch | 🟠 medium |
| AUTH-G08 | **Revocation lag.** Permissions live in the access token, so a revoked role or disabled account stays effective for up to 15 minutes | 🟡 low |
| AUTH-G09 | **Single static HS256 secret.** Symmetric, shared, with no rotation mechanism and no key id in the header. Anyone holding the secret can mint tokens for any user | 🟡 low |
| AUTH-G10 | **No refresh-token reuse detection.** Rotation revokes the presented token, but replaying an already-revoked one only errors — it does not invalidate the descendant token family, which is the signal that a token was stolen | 🟡 low |
| AUTH-G11 | **No login rate limiting or account lockout.** Unbounded password attempts | 🟡 low |
| AUTH-G12 | **No password reset, change, or policy.** Passwords are set at creation only | 🟡 low |
| AUTH-G13 | **Email is globally unique**, so one person cannot hold accounts at two companies | 🟡 low |
| AUTH-G14 | **No authentication audit trail.** Logins, refreshes, grants, and revocations are not recorded as durable events | 🟡 low |

## 10. Migration notes

If an external identity provider is adopted, the split is:

**Moves to the IdP** — credential storage and verification, MFA and passkey
enrolment, SSO federation, session and refresh lifecycle, password reset,
lockout and rate limiting, login audit. `RefreshToken` is deleted outright;
`User.passwordHash` and `UserStatus` become the IdP's concern.

**Stays local** — `Role`, `Permission`, and `UserBranchAccess`. These are
business facts about this domain, not identity. An IdP answers *who is this*;
the application answers *what may they do here*. Pushing branch grants into the
IdP buys a permanent synchronisation job.

**Becomes a projection** — `User` shrinks to a local row keyed by the IdP's
subject claim, holding `companyId` and display name, provisioned on first login.

**Barely changes** — everything outside `identity/security/`. No service or
controller touches a JWT; they consume `AuthPrincipal` and `@PreAuthorize`
strings. The issuer can be swapped behind that seam.

Note that **§9 survives the migration untouched**. G01 through G04 are data-access
defects, not authentication defects: a perfectly valid token from any provider
still reaches an unscoped `findAll()`.

## 11. Open questions

- **Per-branch permission evaluation** (AUTH-G07). Should the active branch
  narrow the effective permission set? Correct, but it means permissions can no
  longer be a static claim.
- **Shared-terminal authentication.** A till is shared across shifts, so
  device-bound passkeys and per-user TOTP fit back-office users and not
  front-of-house. Likely two flows: full OIDC for back office, a device-bound
  terminal session with per-cashier PIN for the POS.
- **Availability.** With an external IdP, an outage stops all logins. What is
  the acceptable degraded mode for a till that is already open?
- **Token TTL versus revocation.** 15 minutes is the current compromise. Shorter
  raises IdP load; longer widens the revocation window.
