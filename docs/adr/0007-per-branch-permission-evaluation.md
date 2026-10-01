# Permissions are weighed at the branch a request acts in

A user holds one role per **Branch**, and a request is authorized with the
permissions of the role at the branch it names in `X-Branch-Id` — not the
union of every role the user holds. A user who is MANAGER at A and CASHIER at
B refunds at A and only sells at B.

The access token carries the user's identity and **branch → role id** for each
live branch, never permission strings. `JwtAuthenticationFilter` resolves each
role through `RolePermissionCache` (one database read per role per minute,
dropped after a role edit commits) and sets the authorities in force:

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
- **Create or edit a role**: a role has no branch, so its permissions must be
  within the union of the actor's.
- An owner passes every rule; only an owner grants, revokes or manages OWNER.

Code that reads several branches in one request, or acts at a branch named in
the body or query rather than the header, must check that branch itself
(`AuthPrincipal.permissionsAt`): the outlet comparison lists only branches
whose role carries `report:read`; a product's availability is listed only
where the role carries `catalog:read`, set only where it carries
`catalog:write`, and priced at a branch only with `catalog:read` there (403;
another company's branch stays 404).

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

- A role edit reaches tokens already issued within a minute — at once on the
  instance that made the edit. A grant or revoke still waits for the access
  token to expire (up to 15 minutes); the `auth_version` claim (Task 36) closes
  that, and its invalidation belongs in `RolePermissionCache`.
- Access tokens issued before this change carry no branch roles and are
  refused with 401, so clients refresh once.
- Switching branch in the frontend can close the page in view: the shell
  returns to the dashboard when the new branch's role does not open it.
- When company-wide grants exist (a nullable `branch_id` on
  `user_branch_access`), company-level permissions should come from them
  instead of the union, and this ADR is superseded.
