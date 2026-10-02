# Permissions are weighed at the branch a request acts in

A user holds one role per **Branch**, and a request is authorized with the
permissions of the role at the branch it names in `X-Branch-Id` — not the
union of every role the user holds. A user who is MANAGER at A and CASHIER at
B refunds at A and only sells at B.

The access token carries the user's identity and **branch → role id** for each
live branch, never permission strings. `JwtAuthenticationFilter` honours a
branch → role pair only while the user still holds that grant
(`UserGrantCache`: the user's current grants on live branches, one read per
user every 30 seconds, dropped after a grant or revoke commits and when a branch
is deleted or deactivated); a pair no longer granted gives nothing, and its
branch is refused as `X-Branch-Id` (403, code `BRANCH_FORBIDDEN`; the
branchless `/auth/me`, `/auth/refresh` and `/auth/logout` ignore the header). A branch the header names that the
user still holds, but under another role than the token says, answers 401
instead: a refresh brings the token up to date. Before refusing on such a
disagreement (another role at that branch, or a branch the token names that the
copy lacks), an instance reads the user's grants afresh when the token was
issued after its copy was read (to the token's `iat` second), as it does for a
token with a higher auth version: a token another instance issued after a
change is honoured, while an older token never makes it read again. Otherwise a token could keep a revoked grant
for its lifetime — and with it whatever a role it names is later given. The
filter resolves each remaining role through `RolePermissionCache` (one read per
role per minute, dropped after a role edit commits) and sets the authorities in
force:

- **Branch-level permissions** (everything except the six below) come from the
  role at the branch the request names. A request that names no branch is
  weighed against every branch the user holds; it cannot reach any branch's
  data that way, because every branch-scoped operation requires the header
  (`BranchContext.require()` and the services' `activeBranch()` refuse
  without it).
- **Company-level permissions** — `company:*`, `user:*`, `role:*`
  (`Permissions.COMPANY_LEVEL`) — are weighed against every branch the user
  holds, whichever branch the request names. Companies, users and roles belong
  to no one branch, and there are no company-wide grants yet, so the union is
  the only honest answer to "may this user administer the company". These are
  exactly the permissions that guard `/companies`, `/users` and `/roles`.

`AuthPrincipal` keeps both views: `permissions()` is what is in force for the
request; `branchIds()`, `permissionsByBranch()` and `allPermissions()` are the
full grants. `/auth/me` returns `permissions` (held at any branch) and
`permissionsByBranch`, and the frontend mirrors the same rule in
`AuthService.permissions` with `COMPANY_LEVEL_PERMISSIONS`.

`AccessPolicy` weighs the actor's full grants, never the branch the request
acts in, and judges each rule **at the branch it affects** — otherwise a
cashier at B who manages A could grant themselves MANAGER at B:

- **Grant or revoke role R at branch X**: the actor holds X, and R's
  permissions are within the actor's own role at X.
- **Manage a user** (profile, password, status, grants): the actor holds every
  branch the user holds, and at each the user's permissions are within the
  actor's there. Equal peers may still manage each other.
- **Create or edit a role**: see below.
- An owner passes every rule; only an owner grants, revokes or manages OWNER.

Role edits: a new role is held by nobody, so creating one needs only
permissions within the union of the actor's — each later grant of it is judged
at its branch. A role already granted is different: whatever an edit **adds**
reaches every holder at once, so the added permissions must be the actor's at
every live branch where the role is granted (and the actor must hold each such
branch) — otherwise a cashier at B who edits roles from A could add refunds to
their own till role. As before, an edit may touch only a role whose current
permissions are within the actor's union, and OWNER is not editable. Removing a
permission needs nothing more: narrowing cannot escalate.

Code that reads several branches in one request, or acts at a branch named in
the body or query rather than the header, must check that branch itself
(`AuthPrincipal.permissionsAt`): the outlet comparison lists only branches
whose role carries `report:read`; a product's availability is listed only
where the role carries `catalog:read`, set only where it carries
`catalog:write`, and priced at a branch only with `catalog:read` there (403;
another company's branch stays 404). Editing or deleting a branch acts on the
branch in the path, so it needs `branch:write` in the caller's role there; an
owner passes.

Granting a role and widening it take the role's row lock (`SELECT … FOR
UPDATE`) before reading it, so the two serialize: a widening sees the grant
made just before it, and a grant sees the permissions just added.

## Considered options

- **Union of every role (before).** Simple, but a cashier at B with a manager
  grant at A could void and refund at B — the grant model meant nothing per
  branch.
- **Union only on a list of branchless paths** (`/users/**`, `/roles/**`,
  `/companies/**`, `/auth/me`). Rejected: it ties authorization to URL
  matching, a branch-scoped endpoint added under those paths would silently get
  the union, and the frontend cannot mirror it per permission. Classifying the
  permissions gives the same result for those endpoints, because they are
  guarded by company-level permissions only.
- **No permissions at all without a branch header.** Stricter, but company
  data (menu, ingredients, suppliers) is read and written without a branch by
  API clients and tests today; the union for header-less requests is safe for
  the reason above.
- **Permissions in the token, per branch.** Keeps requests database-free, but
  the token grows with branches × permissions and a role edit waits for every
  token to expire. Role ids plus a one-minute cache bound both.

## Consequences

- A role edit, grant or revoke reaches tokens already issued within the
  caches' lifetimes (30 seconds for grants, a minute for a role's permissions)
  — at once on the instance that made it — and leaves the token valid.
  Logging in or refreshing re-reads the user's grants, so a new token and the
  check always agree.
- With several instances, another instance can serve stale grants for up to
  30 seconds after a grant or revoke (`UserGrantCache.TTL`), and a role's old
  permissions for up to 60 seconds after an edit (`RolePermissionCache.TTL`).
  A refresh brings a token up to date: it is issued from freshly read grants, so
  it never names a revoked grant, and an instance that finds a token newer than
  its copy of the user (a higher auth version, or another role at the branch
  than the copy holds) reads the user's grants afresh once instead of refusing
  it. A token issued before the change is not newer evidence, so it can pass on
  another instance until the TTL runs out; a role's edited permissions are read
  from the role, so only the 60 seconds bring them in.
- What the caches cannot follow ends the token instead (AUTH-G08): a status
  change, a password change or reset, and a branch closing (whose grants go
  with it) move the user's `auth_version`, which every token carries as `av`,
  and a token whose `av` is no longer the user's answers 401. The version is
  read with the grants, in the same per-user cache entry. A token newer than an
  instance's copy (issued on another instance after the change) makes that
  instance read the version afresh, so a fresh token is never refused; an old
  one may still pass on another instance for up to 30 seconds.
- Every authenticated request reads two caches; a miss costs one query per
  role or three per user, at most once a minute and every 30 seconds.
- Access tokens issued before this change carry no branch roles and are
  refused with 401, so clients refresh once.
- Switching branch in the frontend can close the page in view: the shell
  returns to the dashboard when the new branch's role does not open it.
- When company-wide grants exist (a nullable `branch_id` on
  `user_branch_access`), company-level permissions should come from them
  instead of the union, and this ADR is superseded.
