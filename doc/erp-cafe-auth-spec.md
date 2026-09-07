# ERP Cafe — Authentication & Authorization Spec

**Status:** as-built, describing the system at commit `411f1e7`.

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
| Refresh-token families with reuse detection | ✅ |
| Per-branch role grants | ✅ |
| Fine-grained permissions at the endpoint | ✅ |
| Branch header validated against granted access | ✅ |
| Account lifecycle: enable/disable, profile edit | ✅ |
| Password change (self) and reset (admin) | ✅ |
| Role lifecycle: create, rename, delete | ✅ |
| Failed-login throttling | ✅ per instance, in memory |
| Company scoping below the service layer | ⚠️ convention, applied in every module today |
| MFA / passwordless / SSO federation | ⬜ none |
| Forgotten-password self-service (email flow) | ⬜ none |
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
| `RefreshToken` | `refresh_token` | `userId`, `tokenHash` (SHA-256, unique), `expiresAt`, `revoked`, `familyId`. Every token descended from one login shares a family |

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

Failed attempts are counted by `LoginAttemptService` in a sliding window keyed
by **email and client address**. Past the threshold the endpoint returns **429**
until the window lapses; a successful login clears the counter. Keying on both
is deliberate: an email-only key would let anyone lock a known user out of their
own account. Defaults are 10 failures per 15 minutes, overridable with
`app.security.login.max-failures` and `app.security.login.window`. The counters
are in-memory and per-instance.

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

`POST /api/v1/auth/refresh`. Looks up by hash, rejects expired, **revokes the
presented token**, and mints a fresh pair carrying the same family id. Single-use
rotation, so a stolen token is invalidated the moment the legitimate client next
refreshes.

**Reuse detection.** Presenting a token that was *already rotated away* means
either it was stolen and replayed, or the legitimate holder's copy was. Either
way the family is compromised, so every live token in it is revoked and the user
must sign in again. That revocation commits in its own `REQUIRES_NEW`
transaction: the request itself then fails, and its rollback would otherwise undo
the revocation.

### AUTH-106 — Account and role lifecycle

| Endpoint | Permission | Effect |
|---|---|---|
| `PUT /api/v1/users/{id}` | `user:write` | Edit email and display name |
| `PUT /api/v1/users/{id}/status` | `user:write` | Enable or disable; disabling revokes the user's refresh tokens |
| `POST /api/v1/users/me/password` | authenticated | Change own password, current password required |
| `PUT /api/v1/users/{id}/password` | `user:write` | Administrative reset |
| `PUT /api/v1/roles/{id}` | `role:write` | Rename a custom role |
| `DELETE /api/v1/roles/{id}` | `role:write` | Delete a custom role nobody holds |

Every credential or status change revokes that user's outstanding refresh
tokens, so a disabled or reset account cannot be extended beyond the life of an
access token already in flight.

Guards: you cannot change your own status, since disabling yourself is the one
change you could not undo. The seeded default roles cannot be renamed or deleted,
because the startup reconciliation matches them by name. A role still granted to
someone cannot be deleted.

There is deliberately **no hard delete** for users: orders, shifts and audit
columns reference them, and the email unique constraint covers soft-deleted rows,
so disabling is the correct primitive for offboarding.

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

Step 5 is the weak joint: it is a convention, not a mechanism. Every module
applies it today, and each has a tenant isolation test pinning that, but nothing
stops the next service from forgetting. Row-level security is the intended
backstop, per ADR-0003; it is enabled on `ingredient` only, and stays dormant
until the app connects as a non-superuser database role.

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
    login:
      max-failures: 10     # optional, default 10
      window: PT15M        # optional, default 15 minutes
```

Sessions are stateless, CSRF is disabled (token auth, no cookies), and passwords
are hashed with BCrypt at Spring's default strength.

## 9. Known gaps and defects

### 9.1 Closed

Each is closed by a test that fails against the old behaviour. Kept here because
the ids are cited elsewhere, and because a closed gap is worth distinguishing
from one that was never real.

| ID | Gap | Closed by |
|---|---|---|
| AUTH-G01 | Branch-grant escalation through an unscoped branch existence check | `grantAccess` uses `branchExistsInCompany`; `UserAccessGrantIntegrationTest` |
| AUTH-G02 | `UserService.list()` returned every company's users | `findByCompanyId`; `UserTenantIsolationIntegrationTest` |
| AUTH-G03 | `UserService.get()` was an unscoped `findById` | `findByIdAndCompanyId` returning 404; `UserTenantIsolationIntegrationTest` |
| AUTH-G04 | Four IDORs outside identity | Scoped lookups; `StaffTenantIsolationTest`, `OutletReportTenantIsolationTest` |
| AUTH-G05 | `BranchContextFilter` was a second, unvalidated `BranchContext` writer | Deleted. `JwtAuthenticationFilter` is now the sole writer |
| AUTH-G06 | New permissions backfilled to OWNER only | `DataInitializer.syncDefaultRolePermissions` reconciles **all** default roles, add-only; `DefaultRolePermissionSyncTest` |
| AUTH-G10 | No refresh-token reuse detection | Token families; `RefreshTokenReuseIntegrationTest` |
| AUTH-G11 | No login rate limiting | `LoginAttemptService`; `LoginThrottleIntegrationTest` |
| AUTH-G15 | Organization module was unscoped: company and branch list, get, update and delete resolved with no tenant check, and CASHIER holds `branch:read`. Branch company also came from the request body, so a branch could be created in, or moved to, another tenant | Scoped lookups and principal-derived company; `OrganizationTenantIsolationTest` |
| AUTH-G16 | No account lifecycle: `UserStatus.DISABLED` and the password setter existed but were never called, so an account could not be disabled and a password never changed | `AccountLifecycleIntegrationTest` |
| AUTH-G17 | No role lifecycle: roles could be created but never renamed or deleted | `RoleLifecycleIntegrationTest` |

**Note on G06.** The gap text named `syncOwnerPermissions`. No such method
exists; the reconciliation is `syncDefaultRolePermissions` and covers OWNER,
MANAGER and CASHIER.

### 9.2 Open

| ID | Gap | Severity |
|---|---|---|
| AUTH-G07 | **Permissions union across branches.** A user who is MANAGER at branch A and CASHIER at branch B carries the manager permission set at *both*, because `perms` is a flat union and is not re-evaluated per active branch | 🟠 medium |
| AUTH-G08 | **Revocation lag.** Permissions live in the access token, so a *role or grant* change stays ineffective for up to 15 minutes. Narrowed: status and password changes now revoke refresh tokens, so they cannot be extended past the current access token | 🟡 low |
| AUTH-G09 | **Single static HS256 secret.** Symmetric, shared, with no rotation mechanism and no key id in the header. Anyone holding the secret can mint tokens for any user | 🟡 low |
| AUTH-G12 | **No forgotten-password self-service.** Change and administrative reset now exist (AUTH-106), but a user locked out with no admin available has no email-based recovery. No password complexity policy beyond an 8-character floor | 🟡 low |
| AUTH-G13 | **Email is globally unique**, so one person cannot hold accounts at two companies | 🟡 low |
| AUTH-G14 | **No authentication audit trail.** Logins, refreshes, grants, and revocations are not recorded as durable events | 🟡 low |
| AUTH-G18 | **Login throttle is per-instance and in memory.** It resets on restart and is not shared across replicas, so it degrades as soon as the app is scaled out | 🟡 low |
| AUTH-G19 | **A new branch has no one who can reach it.** All access is per branch, so creating a branch grants nobody access to it, including the owner who created it. Harmless today because permissions are unioned, but it is a hard blocker for AUTH-G07 | 🟠 medium |

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

Note that **the data-access gaps in §9 would have survived the migration
untouched**. G01 through G04 and G15 were not authentication defects: a perfectly
valid token from any provider still reaches an unscoped `findAll()`. They are all
closed now, but the lesson holds for whatever is scoped next — an identity
provider answers *who is this*, never *which rows may they see*.

## 11. Open questions

- **Per-branch permission evaluation** (AUTH-G07). Should the active branch
  narrow the effective permission set? Correct, but it means permissions can no
  longer be a static claim. It is also **blocked by AUTH-G19**: with permissions
  narrowed per branch, an owner would have no way to grant themselves access at a
  branch they cannot yet reach. A company-wide grant, most simply a nullable
  `branch_id` on `user_branch_access`, has to land first. That same nullable
  grant answers what permissions apply on branchless endpoints such as
  `/users` and `/roles`.
- **Shared-terminal authentication.** A till is shared across shifts, so
  device-bound passkeys and per-user TOTP fit back-office users and not
  front-of-house. Likely two flows: full OIDC for back office, a device-bound
  terminal session with per-cashier PIN for the POS.
- **Availability.** With an external IdP, an outage stops all logins. What is
  the acceptable degraded mode for a till that is already open?
- **Token TTL versus revocation.** 15 minutes is the current compromise. Shorter
  raises IdP load; longer widens the revocation window.
