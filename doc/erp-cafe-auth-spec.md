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
| Failed-login throttling | ✅ shared by every instance (`login_attempt`) |
| Company scoping below the service layer | ⚠️ convention, applied in every module today |
| MFA / passwordless / SSO federation | ⬜ none |
| Forgotten-password self-service (email flow) | ✅ single-use emailed link; off in prod until a real mail adapter exists |
| Key rotation | ✅ a key set with key ids; any configured key verifies, the active one signs |
| Login/session audit trail | ✅ `auth_event`, read at `GET /api/v1/auth-events` |

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
`app.security.login.max-failures` and `app.security.login.window`. A second
counter keyed by the address alone (default 20, `max-failures-per-address`)
stops one client spraying guesses across many emails.

The counters live in the `login_attempt` table (AUTH-G18), so every instance
counts the same failures and a restart forgets none. Each row is keyed by the
SHA-256 of its counter's name, so the table holds no email or address. It is
read and written only as the schema owner, since a login runs before any
company is known; the app role holds no privilege on it. Lapsed counters are
purged every 15 minutes. The PIN clock throttle (staff) is still in memory and
per instance.

### AUTH-102 — Token pair

**Access token** — HS256 JWT, 15-minute TTL. The header's `kid` names the key
that signed it, claims:

| Claim | Contents |
|---|---|
| `iss` | `erp-cafe` |
| `aud` | `erp-cafe-api` |
| `sub` | user id |
| `email` | login email |
| `companyId` | the user's company |
| `branchRoles` | branch id → the role id the user holds there (ADR-0007) |
| `av` | the user's auth version when the token was issued |

**Signing keys** (AUTH-G09). `app.security.jwt.keys` lists every key a token may
be signed with, each with a `kid`; exactly one is `active` and signs new tokens.
Verification picks the key the token's `kid` names, and refuses a token naming
no configured key, another issuer, or another audience. A key is rotated without
signing anyone out: add the new key as active and mark the old one inactive, then
remove the old one once the access-token TTL has passed. A token from before the
key set carries no `kid` and is refused; the client refreshes once.

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
the revocation. It first locks the family's live tokens (`SELECT … FOR UPDATE`),
which waits for a rotation in flight, and only then revokes them in a statement
of its own, which sees the token that rotation committed: otherwise the
replacement would outlive the revocation.

### AUTH-106 — Account and role lifecycle

| Endpoint | Permission | Effect |
|---|---|---|
| `PUT /api/v1/users/{id}` | `user:write` | Edit email and display name |
| `PUT /api/v1/users/{id}/status` | `user:write` | Enable or disable; disabling revokes the user's refresh tokens |
| `POST /api/v1/users/me/password` | authenticated | Change own password, current password required |
| `PUT /api/v1/users/{id}/password` | `user:write` | Administrative reset |
| `PUT /api/v1/roles/{id}` | `role:write` | Rename a custom role |
| `DELETE /api/v1/roles/{id}` | `role:write` | Delete a custom role nobody holds |

Every credential or status change moves the user's auth version, so the access
tokens they hold answer 401 at once (AUTH-G08), and disabling or a password
change or reset also revokes their outstanding refresh tokens, so the account
cannot sign back in by refreshing.

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

### AUTH-107 — Audit trail

Every sign-in, failed sign-in, refresh, replayed refresh token and sign-out,
and every grant, revoke, password change or reset and status change, is
recorded in `auth_event`: the user it is about, their company, the type, the
client address (as the login throttle sees it), when, and a `detail` object —
the branch and role of a grant or revoke, the new status, the token family
(`session`) of a sign-in, refresh or sign-out, the attempted email and reason
of a failed sign-in, and `actorUserId` whenever someone else made the change.
An access change is recorded in the transaction that makes it; a sign-in,
refresh or sign-out is written as the schema owner, since no company is in
force yet, and a failed one in a transaction of its own so it outlives the
failed request. A failed sign-in for an email no account has is kept for no
company, where only the owner sees it; a throttled attempt (429) is not
recorded, so a flood cannot fill the table.

`GET /api/v1/auth-events` (`user:read`) pages through the caller's company's
trail, newest first; `userId` narrows it to one user. Row-level security keeps
each company to its own rows, and the app role may add rows but never change or
delete one. Nothing purges the table yet.

### AUTH-108 — Password reset by email

`POST /api/v1/auth/password-reset/request` with `{email}` always answers
**202**, whether or not the email has an account. For an active account it
stores a token — 32 random bytes, of which only the SHA-256 is kept — and mails
a link to `app.security.password-reset.link` with `?token=…` through the
`MailSender` port. The token expires after 30 minutes
(`app.security.password-reset.ttl`). An account with three open links gets no
more until one closes, so a flood of requests fills neither the table nor an
inbox. A mail that fails to send is logged, never reported to the caller.

`POST /api/v1/auth/password-reset/confirm` with `{token, newPassword}` first
checks, read-only, that the token names a live link, and answers **422** at
once when it does not: the endpoint is public, and a junk token costs no
BCrypt. It then claims the token in one statement, so it works once even when confirmed twice at once,
and sets the password (8–100 characters, at most 72 bytes). It closes the
account's other open links, revokes its refresh tokens, moves its auth version
(so its access tokens end, AUTH-G08), and records `PASSWORD_RESET` with
`via: EMAIL`. A used, expired or unknown token answers **422**; a disabled
account's too. Both endpoints are public and run as the schema owner, which
alone issues and claims links.

An open link also closes whenever the account's access or email changes: a
status change, a password change or reset and a branch closing (wherever the
auth version moves), and an email change, so a link mailed to an old address
cannot reset the account afterwards. Those changes run as the app role, which
may set `used_at` on its company's links but cannot read a token's hash, add a
link or delete one.

The frontend's sign-in page links to *Forgot password*, which asks for the
email; the mailed link opens *Choose a new password*, which signs the browser
out and returns to sign-in.

**Switched on or off** by `app.security.password-reset.enabled` (default
true; false in `application-prod.yml`). While off, both endpoints answer
**404** `NOT_FOUND`, and the *Forgot password* page tells the user to ask a
manager or the owner, who can reset it (AUTH-106). While on, the app refuses
to start unless a `MailSender` bean exists and `link` is an absolute URL. The
only adapter today, `LoggingMailSender`, writes each mail to the log, and a
reset link there is a credential, so it serves only the `dev` and `test`
profiles. **Turning reset on in prod needs a real `MailSender` adapter** (and
`APP_PUBLIC_URL`). That adapter must send after the request's transaction
commits and off the request thread: an account that exists already costs a
token insert more than one that does not, and a synchronous send would make
the difference large enough to tell which emails have an account.

## 7. Request authorization pipeline

For every request, in order:

1. **`JwtAuthenticationFilter`** (registered before `UsernamePasswordAuthenticationFilter`)
   parses the `Authorization: Bearer` token into an `AuthPrincipal`.
   Invalid or expired → **401** with a problem+json body; so is a token whose
   `av` is no longer the user's auth version, and one naming another role at
   the `X-Branch-Id` branch than the user now holds there.
2. **Branch resolution.** If `X-Branch-Id` is present: malformed UUID → **400**;
   a branch not in the principal's granted set → **403** with the code
   `BRANCH_FORBIDDEN`; otherwise it is placed in `BranchContext` (a
   thread-local) for the request. `/auth/me`, `/auth/refresh` and `/auth/logout`
   act in no branch and ignore the header, so a user whose active branch was
   revoked or closed can still read who they are, refresh and sign out. The
   frontend answers `BRANCH_FORBIDDEN` by re-reading `/auth/me`, which moves
   it to a branch the user still holds, and returning to the dashboard; the
   refused request is never retried. It does so on the retry after a refresh
   too, which is how a closed active branch first shows (closing it ends the
   holder's token), once however many requests fail together, and not again
   for a branch it has already left.
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
      keys:
        - kid: <key id>
          secret: <HS256 key, 32+ bytes>
          active: true
      access-token-ttl: 15m
      refresh-token-ttl: 30d
    bootstrap:
      admin-email: <seed admin>
      admin-password: <seed password>
    password-reset:
      enabled: true        # optional, default true; false in prod until a real MailSender exists
      ttl: 30m             # optional, default 30 minutes
      link: <frontend base URL>/reset-password
    login:
      max-failures: 10     # optional, default 10
      window: PT15M        # optional, default 15 minutes
      max-failures-per-address: 20   # optional, default 20
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
| AUTH-G08 | Revocation lag: a role, grant, status or password change stayed ineffective for up to 15 minutes | Grants, revokes and role edits reach issued tokens through the grant and role caches (ADR-0007); a status change, a password change or reset, and a branch closing move the user's `auth_version`, which ends every token carrying the old `av`. `AuthGapsIntegrationTest`. Other instances follow within the caches' lifetimes (30 s and 60 s) |
| AUTH-G14 | No authentication audit trail: logins, refreshes, grants and revocations were not recorded as durable events | `auth_event` and `GET /api/v1/auth-events` (AUTH-107); `AuthGapsIntegrationTest`, `RlsEverywhereTest` |
| AUTH-G18 | The login throttle was per instance and in memory: it reset on restart and was not shared across replicas | Counters in the `login_attempt` table; `AuthGapsIntegrationTest`, `LoginAttemptServiceTest` |
| AUTH-G12 | No forgotten-password self-service: a user locked out with no admin available had no email-based recovery | Single-use emailed reset link (AUTH-108); `AuthGapsIntegrationTest`, `forgot-password.spec.ts`, `reset-password.spec.ts` |
| AUTH-G09 | A single static HS256 secret, with no key id in the header and no way to rotate it without signing everyone out | A key set with `kid`, `iss` and `aud` checked on every token; `JwtServiceTest`, `AuthGapsIntegrationTest`. The keys are still symmetric: anyone holding one can mint tokens |

**Note on G06.** The gap text named `syncOwnerPermissions`. No such method
exists; the reconciliation is `syncDefaultRolePermissions` and covers OWNER,
MANAGER and CASHIER.

### 9.2 Open

| ID | Gap | Severity |
|---|---|---|
| AUTH-G07 | **Permissions union across branches.** A user who is MANAGER at branch A and CASHIER at branch B carries the manager permission set at *both*, because `perms` is a flat union and is not re-evaluated per active branch | 🟠 medium |
| AUTH-G13 | **Email is globally unique**, so one person cannot hold accounts at two companies. **Deferred (ruling, Task 36):** one email across companies needs a company choice at sign-in, and conflicts with the planned move to an identity provider (§10), where an account is the IdP's subject rather than a company's row; it is settled there | 🟡 low |
| AUTH-G12 (rest) | No password complexity policy beyond an 8-character floor. The emailed reset (AUTH-108) closed the recovery half; it is off in prod until a real mail adapter exists | 🟡 low |
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
