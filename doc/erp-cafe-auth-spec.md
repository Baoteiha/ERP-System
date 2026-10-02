# ERP Cafe — Authentication & Authorization Spec

**Status:** as-built, brought up to date on 2026-10-02 after the code-review fixes
on branch `fix/code-review` (it described commit `411f1e7` before them).

This documents what exists today, ahead of a possible migration to an external
identity provider. It is a description, not a proposal: where the code and its
own javadoc disagree, this file follows the code.

## 1. Goal

Establish who a request is from (**authentication**) and what they may do
(**authorization**), across a multi-tenant deployment where one instance serves
many café companies, each with several branches.

Three questions have to be answered on every request:

1. **Who are you?** — resolved from a signed access token.
2. **What may you do?** — the permissions of the role you hold at the branch you act in; the token names the role per branch, and the permissions are read from the role (ADR-0007).
3. **Where may you do it?** — which company, and which branch of it.

Questions 1 and 2 are solved centrally and consistently. Question 3 was solved
centrally for *branch* and by per-service convention for *company*, which was the
origin of most defects in §9; the company is now scoped by one mechanism (the
tenant seam, ADR-0008) with row-level security behind it.

## 2. Current state

| Capability | Status |
|---|---|
| Email + password login | ✅ |
| Stateless JWT access tokens (HS256) | ✅ |
| Opaque, hashed, rotating refresh tokens | ✅ |
| Refresh-token families with reuse detection | ✅ |
| Per-branch role grants, evaluated at the branch a request acts in | ✅ ADR-0007 |
| Fine-grained permissions at the endpoint | ✅ |
| Branch header validated against granted access | ✅ |
| Account lifecycle: enable/disable, profile edit | ✅ |
| Password change (self) and reset (admin) | ✅ |
| Role lifecycle: create, rename, delete | ✅ |
| Failed-login throttling | ✅ shared by every instance (`login_attempt`) |
| Tokens end when access changes | ✅ `auth_version` and the grant caches (AUTH-G08) |
| Refresh token in an HttpOnly cookie | ✅ AUTH-102 |
| Company scoping below the service layer | ✅ Hibernate `@TenantId` (ADR-0008) plus row-level security on every tenant table, enforced for the non-superuser `erp_app` role |
| MFA / passwordless / SSO federation | ⬜ none |
| Forgotten-password self-service (email flow) | ✅ single-use emailed link; off in prod until a real mail adapter exists |
| Key rotation | ✅ a key set with key ids; any configured key verifies, the active one signs |
| Login/session audit trail | ✅ `auth_event`, read at `GET /api/v1/auth-events` |

## 3. Domain model

| Entity | Table | Notes |
|---|---|---|
| `User` | `app_user` | `companyId`, `email` (stored lowercase), `passwordHash`, `fullName`, `status`, `auth_version`. Email is unique **globally**, not per company (AUTH-G13). Soft-deletable, versioned |
| `UserStatus` | — | `ACTIVE`, `DISABLED` |
| `Role` | `role` | `companyId`, `name`, `description`, and a many-to-many to `Permission` (EAGER). Unique on `(company_id, name)` |
| `Permission` | `permission` | `name` (globally unique), `description`. Reference data synced from code at startup; never created at runtime |
| `UserBranchAccess` | `user_branch_access` | Grants one user one role at one branch. Unique on `(user_id, branch_id)` — **one role per user per branch** |
| `RefreshToken` | `refresh_token` | `userId`, `tokenHash` (SHA-256, unique), `expiresAt`, `revoked`, `familyId`. Every token descended from one login shares a family. Rows expired for over 7 days are purged daily |
| `AuthEvent` | `auth_event` | The audit trail (AUTH-107) |
| `LoginAttempt` | `login_attempt` | The login throttle's counters (AUTH-101); owner-only |
| `PasswordResetToken` | `password_reset_token` | Hashed, single-use reset links (AUTH-108) |

The model is **RBAC with a branch dimension**. A user holds a role *per branch*,
so the same person can be MANAGER at one branch and CASHIER at another. A request
is authorized with the permissions of the role at the branch it names in
`X-Branch-Id`; only the company-level permissions (`company:*`, `user:*`,
`role:*`) are weighed across every branch the user holds (ADR-0007, AUTH-G07).

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

Looks the user up by email (case-insensitively; emails are stored lowercase),
verifies the password with BCrypt, rejects a `DISABLED` account (422), and
issues a token pair. Failure is a single generic
"Invalid email or password" for both unknown email and wrong password, so the
endpoint does not disclose which emails exist.

Failed attempts (a wrong password, or a disabled account) are counted by
`LoginAttemptService` in a window that opens at the first failure, keyed by
**email and client address**. Past the threshold the endpoint returns **429**
until the window lapses; a successful login clears the email-and-address
counter (the address counter is left to expire). Keying on both
is deliberate: an email-only key would let anyone lock a known user out of their
own account. Defaults are 10 failures per 15 minutes, overridable with
`app.security.login.max-failures` and `app.security.login.window`. The client
address is the TCP peer, or `X-Forwarded-For` only when the peer is a trusted
proxy (private and loopback ranges by default; others must be listed in
`server.tomcat.remoteip.internal-proxies`, see `backend/ARCHITECTURE.md` §10;
`ForwardedAddressThrottleHttpTest`). A second
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

**The refresh cookie** (FE-14). The refresh token never appears in a response
body. Login and refresh set it as
`Set-Cookie: erpcafe_refresh=<token>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=<refresh TTL>`,
so page scripts cannot read it and the browser sends it to the auth endpoints
alone. The response body is `{accessToken, tokenType}`. `Secure` follows
`app.security.refresh-cookie.secure`: true by default and in prod, false in the
dev and test configuration (plain HTTP on localhost); no code looks at a profile
name. `ProfileBindingTest` pins prod to `true`. The API sends no CORS headers;
should it ever be served cross-origin, credentials must be allowed only for the
explicit configured origins, never `*`.

**Deploy note (R50).** Migration `V27_001` revokes every live refresh token, so
tokens handed out in a body before the cookie existed cannot be replayed from a
saved copy. Everyone signs in once after this deploy;
`RefreshTokenRevocationMigrationTest` pins it.

**Known limit: a cookie can outlive a logout (R51).** Script cannot delete an
HttpOnly cookie, so only the server's `Set-Cookie: ...; Max-Age=0` clears it. If
the sign-out call times out (3 s), the app is offline, or the logout is local
only (after a failed refresh), the signed-out browser keeps a refresh cookie
that is still live. The app never sends it without its session flag, and the
next login overwrites it, but anyone at that till with a console could use it
until then. There is no logout-pending retry; the shared-till reset flow does
call the server logout.

**The CSRF header.** `SameSite=Strict` is the first defence; the second is that
`/auth/refresh` and `/auth/logout` demand `X-Requested-With: erpcafe` and answer
403 `FORBIDDEN` without it. A cross-site request cannot add a custom header
without a CORS preflight, which this API never grants. Spring Security's CSRF
filter stays off (stateless, no session).

### AUTH-103 — Refresh with rotation

`POST /api/v1/auth/refresh` with the `erpcafe_refresh` cookie and the
`X-Requested-With: erpcafe` header; there is no request body, and one sent is
ignored. Without the header: 403 `FORBIDDEN`. Without the cookie: 401
`UNAUTHORIZED`. Looks the token up by hash. A refresh token is a credential, so
one that is unknown, expired, revoked or replayed answers 401 `UNAUTHORIZED`
too, like a missing cookie; a disabled account answers 422 `BUSINESS_RULE`, as at
login. Otherwise it **rotates the presented token** (revokes it and stamps its
`rotated_at`) and mints a fresh pair carrying the same family id: a new access token
in the body and the rotated refresh token in a new `Set-Cookie`. Single-use
rotation, so a stolen token is invalidated the moment the legitimate client next
refreshes. The frontend never answers a 401 from `/auth/refresh` with another
refresh: it signs out locally (`http.interceptors.spec.ts`).

**Two refreshes at once.** Two tabs of one browser share the cookie, so both may
present the same token in the same instant. Each rotation writes its row under
`@Version`: one wins (200, rotated cookie), and the other either loses that race
(409 `CONFLICT`, its transaction rolled back, the winner's session untouched) or,
having read the row after the winner committed, sees a spent token and is treated
as a replay (401, the family revoked, the winner's session with it).
`RefreshTokenReuseIntegrationTest` pins that exactly one wins and the loser fails
cleanly, never with a 500; the 409 outcome is the one seen in practice. There is
no reuse grace window. The frontend avoids the race itself: tabs refresh inside
one Web Lock, and a tab that finds a different access token in storage than the
one it began with adopts that token instead of calling the server.

**Reuse detection.** Presenting a token that was *already rotated away* (its
`rotated_at` set) means either it was stolen and replayed, or the legitimate
holder's copy was. Either way the family is compromised, so every live token in
it is revoked, `REFRESH_REUSE_DETECTED` goes on the audit trail, and the user
must sign in again. That revocation commits in its own `REQUIRES_NEW`
transaction: the request itself then fails, and its rollback would otherwise undo
the revocation. It first locks the family's live tokens (`SELECT … FOR UPDATE`),
which waits for a rotation in flight, and only then revokes them in a statement
of its own, which sees the token that rotation committed: otherwise the
replacement would outlive the revocation.

**Revoked is not replayed.** A token revoked any other way (a logout, disabling
the account, a password change or reset, a family revocation, or `V27_001`) was
ended on purpose, so presenting it is refused like an expired one (401) and
nothing else happens: no family revocation and no audit event. Otherwise every
browser signed in before the cookie deploy, every open tab of a disabled user and
every second tab after a logout would be logged as a token theft. `V28_001` added
`rotated_at`; rows from before it count as revoked, not rotated.
`RefreshTokenReuseIntegrationTest` pins both sides.

### AUTH-106 — Account and role lifecycle

| Endpoint | Permission | Effect |
|---|---|---|
| `PUT /api/v1/users/{id}` | `user:write` | Edit email and display name |
| `PUT /api/v1/users/{id}/status` | `user:write` | Enable or disable; disabling revokes the user's refresh tokens |
| `POST /api/v1/users/me/password` | authenticated | Change own password, current password required |
| `PUT /api/v1/users/{id}/password` | `user:write` | Administrative reset |
| `PUT /api/v1/roles/{id}` | `role:write` | Rename a custom role |
| `DELETE /api/v1/roles/{id}` | `role:write` | Delete a custom role nobody holds |

A status change (enable or disable), a password change and a reset move the
user's auth version, so the access tokens they hold answer 401 at once
(AUTH-G08); disabling, a password change or reset also revokes their
outstanding refresh tokens, so the account cannot sign back in by refreshing.
An email edit moves no version and revokes nothing, but closes any open reset
link (AUTH-108).

Guards: you cannot change your own status, since disabling yourself is the one
change you could not undo. The seeded default roles cannot be renamed or deleted,
because the startup reconciliation matches them by name. A role still granted to
someone cannot be deleted.

There is deliberately **no hard delete** for users: orders, shifts and audit
columns reference them, and the email unique constraint covers soft-deleted rows,
so disabling is the correct primitive for offboarding.

### AUTH-104 — Logout

`POST /api/v1/auth/logout` revokes the refresh token in the cookie. It also
requires `X-Requested-With: erpcafe` (403 `FORBIDDEN` without it), so a
cross-site page cannot force a sign-out. It always answers 204 and always clears
the cookie (`Max-Age=0`, same name, path and attributes), whether the token was
live, already revoked, or absent. Outstanding access tokens remain valid until
they expire (up to 15 minutes).

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
of a failed sign-in, and `actorUserId` on a change someone made to another
user (a grant, revoke, status change, administrative reset, branch closing),
passed by the code that made it. The user's own sign-in, refresh, sign-out or
password change names no actor, whatever access token the request carried, and
nor does a change someone makes to themselves (an owner closing a branch they
held, an administrator resetting their own password).
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
cannot reset the account afterwards. Each closes them in its own transaction.
A reset by email does so as the schema owner, like the rest of the
confirmation; every other change runs as the app role, which may only close its
company's links (set `used_at`, never clear it, so a used link cannot be
reopened) and cannot read a token's hash, add a link or delete one.

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
   parses the `Authorization: Bearer` token into an `AuthPrincipal`, and sets
   the token's company as the request's `TenantContext` (ADR-0008).
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
3. **Authorities** are the permission strings in force verbatim, plus each role
   name prefixed `ROLE_`. The permissions in force come from the role the user
   holds at the branch named in `X-Branch-Id` (all held branches when none is
   named), plus the company-level ones from any held branch (ADR-0007). The
   token carries role ids, resolved through `UserGrantCache` (30 s) and
   `RolePermissionCache` (60 s).
4. **`@PreAuthorize("hasAuthority('x:y')")`** on controller methods enforces the
   permission for that endpoint.
5. **Services** take the company from `TenantContext`, which Hibernate applies to
   every query and insert of company-owned rows (ADR-0008), and the branch from
   `BranchContext.require()`, which they apply to branch-scoped queries
   themselves.
6. **`finally`** clears `BranchContext`, `TenantContext` and the `SecurityContext`.

Company scoping is a mechanism now, not a convention: a company-owned entity
extends `TenantOwnedEntity`, and another company's id is simply not found (404).
Branch scoping (orders, stock, purchase orders) is still applied by each service
through `BranchContext`, pinned by each module's tenant isolation test.
Row-level security is the backstop (ADR-0003): migration V22 enables it on every
tenant table, and the app connects as `erp_app`, a role that cannot bypass it
(`RlsEverywhereTest`, `AppDatabaseRoleTest`). Login, refresh, logout, startup and
the cross-company checks run as the schema owner through `SystemTransactions`.

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
    refresh-cookie:
      secure: true         # optional, default true; false in the dev and test profiles
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

Sessions are stateless and passwords are hashed with BCrypt at Spring's default
strength. Spring's CSRF filter is off: the access token travels in a header, and
the one cookie, the refresh token's, is guarded by `SameSite=Strict` and the
`X-Requested-With` header (AUTH-102). Production supplies the secrets and the
database credentials in its environment: see `backend/ARCHITECTURE.md` §10.

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
| AUTH-G07 | **Permissions unioned across branches**: a MANAGER at A and CASHIER at B carried the manager set at both | Permissions are weighed at the branch a request names; the token carries role ids per branch (ADR-0007). Only `company:*`, `user:*` and `role:*` are weighed across every branch held, until company-wide grants exist. `PerBranchPermissionTest#refund_isWeighedAgainstTheRoleAtTheActiveBranch`, `#companyLevelAdministration_isWeighedAgainstEveryBranchHeld`, `#grantingYourselfMoreAtABranch_isJudgedAtThatBranch` |
| AUTH-G19 | A new branch had no one who could reach it | The creator gets OWNER there from live grants, on an active new branch only. `BranchLifecycleIntegrationTest#ownerCreatesBranch_ownerSeesItInNextMe`, `#nonOwnerCreatesBranch_getsOwnRoleThere_orIsRefusedWithoutHeader`, `#creatingAnInactiveBranch_writesNoGrant` |
| AUTH-G09 | A single static HS256 secret, with no key id in the header and no way to rotate it without signing everyone out | A key set with `kid`, `iss` and `aud` checked on every token; `JwtServiceTest`, `AuthGapsIntegrationTest`. The keys are still symmetric: anyone holding one can mint tokens |

**Fixed without a gap id.** A MANAGER could escalate to OWNER by granting
themselves the role, resetting or demoting an owner, or widening a role beyond
their own permissions: `AccessPolicy` now judges each rule at the branch it
affects (`PrivilegeEscalationIntegrationTest`, `AccessPolicyTest`). The app
used to start with no profile and the committed dev JWT secret: it has no default
profile now, and `JwtSecretGuard` refuses a committed secret outside `dev` and
`test` (`JwtSecretGuardTest`). `X-Forwarded-For` is honoured only from trusted proxies
(`ForwardedAddressThrottleHttpTest`).

**Note on G06.** The gap text named `syncOwnerPermissions`. No such method
exists; the reconciliation is `syncDefaultRolePermissions` and covers OWNER,
MANAGER and CASHIER.

### 9.2 Open

| ID | Gap | Severity |
|---|---|---|
| AUTH-G13 | **Email is globally unique**, so one person cannot hold accounts at two companies. **Deferred (ruling, Task 36):** one email across companies needs a company choice at sign-in, and conflicts with the planned move to an identity provider (§10), where an account is the IdP's subject rather than a company's row; it is settled there | 🟡 low |
| AUTH-G12 (rest) | No password complexity policy beyond an 8-character floor. The emailed reset (AUTH-108) closed the recovery half; it is off in prod until a real mail adapter exists | 🟡 low |

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

- **Company-wide grants.** Permissions are evaluated per branch (AUTH-G07 and
  G19 are closed), but `company:*`, `user:*` and `role:*` are still the union over
  every branch held, since companies, users and roles belong to no branch. A
  nullable `branch_id` on `user_branch_access` would give them a proper source,
  and would supersede ADR-0007.
- **Shared-terminal authentication.** A till is shared across shifts, so
  device-bound passkeys and per-user TOTP fit back-office users and not
  front-of-house. Likely two flows: full OIDC for back office, a device-bound
  terminal session with per-cashier PIN for the POS.
- **Availability.** With an external IdP, an outage stops all logins. What is
  the acceptable degraded mode for a till that is already open?
- **Token TTL versus revocation.** 15 minutes is the current compromise. Shorter
  raises IdP load; longer widens the revocation window.
