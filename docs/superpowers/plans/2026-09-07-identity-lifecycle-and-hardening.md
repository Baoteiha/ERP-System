# Identity Lifecycle and Auth Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the authentication and authorization system by adding the account and role lifecycle operations that are entirely missing today, hardening the login and refresh paths, and correcting the spec's stale gap list.

**Architecture:** The identity module already has a sound shape: a domain with users, roles, permissions and per-branch grants; an application layer holding use cases; and a stateless JWT security layer. Nothing structural changes. This plan fills in the operations that were never written, adds two well-understood defences to the token endpoints, and brings the spec back in line with the code. Account state changes revoke the user's refresh tokens, which also narrows the revocation lag in AUTH-G08 to the access-token lifetime.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Spring Security, BCrypt, JJWT, Flyway, JUnit 5, MockMvc, Testcontainers PostgreSQL, AssertJ, JsonPath.

**Spec:** `doc/erp-cafe-auth-spec.md`

## Global Constraints

- Cross-tenant access returns **404**, never 403. All user and role lookups go through the already-scoped `UserService.get` and the company check in `RoleService`.
- Exception to HTTP mapping, per `common/web/GlobalExceptionHandler`: `ResourceNotFoundException` is 404, `BusinessRuleException` is 422, `ConflictException` is 409. This plan adds `TooManyRequestsException` as 429.
- Passwords are never returned, logged, or echoed. Only BCrypt hashes are persisted, and only SHA-256 hashes of refresh tokens.
- Any change that invalidates a session must revoke that user's outstanding refresh tokens.
- Password inputs keep the existing validation floor from `CreateUserRequest`: `@NotBlank @Size(min = 8, max = 100)`.
- Integration tests extend `vn.essvn.erpcafe.support.AbstractIntegrationTest`.
- Commit subjects use the `[Action] Feature` format. Never add a Co-Authored-By trailer.
- Run tests with `./gradlew test` from `backend/`. Docker must be running for Testcontainers.

---

## File Structure

**Created:**
- `identity/security/LoginAttemptService.java` — in-memory failed-login throttle keyed by email and client address.
- `common/exception/TooManyRequestsException.java` — maps to 429.
- `src/main/resources/db/migration/V10_001__add_refresh_token_family.sql` — adds the token family column.
- `src/test/java/vn/essvn/erpcafe/identity/AccountLifecycleIntegrationTest.java`
- `src/test/java/vn/essvn/erpcafe/identity/RoleLifecycleIntegrationTest.java`
- `src/test/java/vn/essvn/erpcafe/identity/LoginThrottleIntegrationTest.java`
- `src/test/java/vn/essvn/erpcafe/identity/RefreshTokenReuseIntegrationTest.java`

**Modified:**
- `identity/application/UserService.java` — profile update, status change, password change and reset, token revocation.
- `identity/application/RoleService.java` — rename and delete, guarded.
- `identity/application/AuthService.java` — throttle hook, token families, reuse detection.
- `identity/domain/RefreshToken.java` — family id.
- `identity/persistence/RefreshTokenRepository.java`, `identity/persistence/UserBranchAccessRepository.java` — new finders.
- `identity/web/UserController.java`, `identity/web/UserDtos.java`, `identity/web/RoleController.java`, `identity/web/RoleDtos.java`, `identity/web/AuthController.java` — new endpoints and payloads.
- `common/web/GlobalExceptionHandler.java` — 429 mapping.
- `doc/erp-cafe-auth-spec.md` — gap list truth pass.

---

### Task 1: Account lifecycle

Adds profile update, enable and disable, self-service password change, and admin password reset. Deliberately **no hard delete**: orders, shifts and audit columns reference users, the email unique constraint applies to soft-deleted rows too, and disabling is the correct primitive for offboarding.

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/persistence/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/application/UserService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/web/UserDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/web/UserController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/AccountLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `UserService.get(UUID)` (already company-scoped), `CurrentUser.require().userId()`, `PasswordEncoder`.
- Produces:
  - `RefreshTokenRepository.findByUserIdAndRevokedFalse(UUID) : List<RefreshToken>`
  - `UserService.updateProfile(UUID id, String email, String fullName) : User`
  - `UserService.changeStatus(UUID id, UserStatus status) : User`
  - `UserService.changeOwnPassword(String currentPassword, String newPassword) : void`
  - `UserService.resetPassword(UUID id, String newPassword) : void`
  - DTOs `UpdateUserRequest(String email, String fullName)`, `UpdateStatusRequest(UserStatus status)`, `ChangePasswordRequest(String currentPassword, String newPassword)`, `ResetPasswordRequest(String newPassword)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/vn/essvn/erpcafe/identity/AccountLifecycleIntegrationTest.java`:

```java
package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/** Account lifecycle: profile edit, enable/disable, password change and reset. */
class AccountLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private UserRepository userRepository;

    /** Creates a user in the admin's own company and returns its id. */
    private String createUser(String password) throws Exception {
        int n = SEQ.incrementAndGet();
        String json = mockMvc.perform(post("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"staff%d@esscafe.vn","password":"%s","fullName":"Staff %d"}
                                """.formatted(n, password, n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    @Test
    void updateProfile_changesNameAndEmail() throws Exception {
        String id = createUser("initial1234");

        mockMvc.perform(put("/api/v1/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"renamed%s@esscafe.vn\",\"fullName\":\"Renamed\"}"
                                .formatted(id.substring(0, 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed"));
    }

    @Test
    void disable_blocksLoginAndRevokesRefreshTokens() throws Exception {
        String id = createUser("workingpw123");
        String email = userRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getEmail();

        String refresh = JsonPath.read(loginBody(email, "workingpw123"), "$.refreshToken");

        mockMvc.perform(put("/api/v1/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // password is still correct, but the account is disabled
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"workingpw123\"}".formatted(email)))
                .andExpect(status().isUnprocessableEntity());

        // the session issued before disabling can no longer be extended
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refresh)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enable_restoresLogin() throws Exception {
        String id = createUser("workingpw123");
        String email = userRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getEmail();

        for (String status : new String[] {"DISABLED", "ACTIVE"}) {
            mockMvc.perform(put("/api/v1/users/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"%s\"}".formatted(status)))
                    .andExpect(status().isOk());
        }

        assertThat(accessToken(email, "workingpw123")).isNotBlank();
    }

    @Test
    void disablingYourself_isRejected() throws Exception {
        String meId = JsonPath.read(
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/auth/me")
                                .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                        .andReturn().getResponse().getContentAsString(),
                "$.userId");

        mockMvc.perform(put("/api/v1/users/" + meId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changeOwnPassword_requiresTheCurrentOne_andRotatesCredentials() throws Exception {
        String id = createUser("oldpassword1");
        String email = userRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getEmail();
        String token = accessToken(email, "oldpassword1");

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrongpassword\",\"newPassword\":\"newpassword1\"}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/api/v1/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}"))
                .andExpect(status().isNoContent());

        assertThat(accessToken(email, "newpassword1")).isNotBlank();
    }

    @Test
    void adminResetPassword_setsANewOne() throws Exception {
        String id = createUser("forgotten123");
        String email = userRepository.findById(java.util.UUID.fromString(id)).orElseThrow().getEmail();

        mockMvc.perform(put("/api/v1/users/" + id + "/password")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"resetbyadmin1\"}"))
                .andExpect(status().isNoContent());

        assertThat(accessToken(email, "resetbyadmin1")).isNotBlank();
    }
}
```

Add this static import at the top of the file:

```java
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*AccountLifecycleIntegrationTest*'`
Expected: FAIL with 405 Method Not Allowed or 404 on every new route, because none of these endpoints exist.

- [ ] **Step 3: Add the refresh-token finder**

In `identity/persistence/RefreshTokenRepository.java`, add the import and method:

```java
import java.util.List;
```

```java
    /** Outstanding tokens for a user — revoked in bulk when their credentials or status change. */
    List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);
```

- [ ] **Step 4: Add the service methods**

In `identity/application/UserService.java`, add these imports:

```java
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.domain.UserStatus;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
```

Add `RefreshTokenRepository refreshTokenRepository` as a constructor parameter and field, then append these methods:

```java
    /** Updates a user's display name and login email. */
    public User updateProfile(UUID id, String email, String fullName) {
        User user = get(id);
        if (!user.getEmail().equals(email) && userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already exists: " + email);
        }
        user.setEmail(email);
        user.setFullName(fullName);
        return user;
    }

    /**
     * Enables or disables an account. Disabling revokes every outstanding refresh
     * token, so the account cannot be extended past the current access token's life.
     * Disabling yourself is refused: it is the one change nobody can undo for themselves.
     */
    public User changeStatus(UUID id, UserStatus status) {
        if (id.equals(currentUser.require().userId())) {
            throw new BusinessRuleException("You cannot change your own account status");
        }
        User user = get(id);
        user.setStatus(status);
        if (status == UserStatus.DISABLED) {
            revokeAllTokens(id);
        }
        return user;
    }

    /** Self-service password change; the current password must be presented. */
    public void changeOwnPassword(String currentPassword, String newPassword) {
        UUID userId = currentUser.require().userId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        revokeAllTokens(userId);
    }

    /** Administrative reset; does not require the old password, but does require user:write. */
    public void resetPassword(UUID id, String newPassword) {
        User user = get(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        revokeAllTokens(user.getId());
    }

    /** Revokes every outstanding refresh token for a user. */
    private void revokeAllTokens(UUID userId) {
        refreshTokenRepository.findByUserIdAndRevokedFalse(userId).forEach(RefreshToken::revoke);
    }
```

`User.setEmail` does not exist yet. Add it to `identity/domain/User.java` beside `getEmail`:

```java
    public void setEmail(String email) {
        this.email = email;
    }
```

- [ ] **Step 5: Add the DTOs**

In `identity/web/UserDtos.java`, add these records inside the class:

```java
    public record UpdateUserRequest(
            @NotBlank @Email String email,
            @Size(max = 255) String fullName) {
    }

    public record UpdateStatusRequest(
            @NotNull UserStatus status) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }
```

- [ ] **Step 6: Add the endpoints**

In `identity/web/UserController.java`, add these imports:

```java
import org.springframework.web.bind.annotation.PutMapping;

import vn.essvn.erpcafe.identity.web.UserDtos.ChangePasswordRequest;
import vn.essvn.erpcafe.identity.web.UserDtos.ResetPasswordRequest;
import vn.essvn.erpcafe.identity.web.UserDtos.UpdateStatusRequest;
import vn.essvn.erpcafe.identity.web.UserDtos.UpdateUserRequest;
```

Then add these handlers:

```java
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:write')")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userMapper.toResponse(userService.updateProfile(id, request.email(), request.fullName()));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:write')")
    public UserResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return userMapper.toResponse(userService.changeStatus(id, request.status()));
    }

    /** Self-service: any authenticated user may change their own password. */
    @PostMapping("/me/password")
    public ResponseEntity<Void> changeOwnPassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    @PreAuthorize("hasAuthority('user:write')")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.newPassword());
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*AccountLifecycleIntegrationTest*'`
Expected: PASS, all six tests green.

- [ ] **Step 8: Run the full suite and commit**

Run: `cd backend && ./gradlew test`

```bash
git add backend/src/main/java/vn/essvn/erpcafe/identity backend/src/test/java/vn/essvn/erpcafe/identity
git commit -m "[Add] Account lifecycle: profile, enable/disable, password change and reset"
```

---

### Task 2: Role lifecycle

**Files:**
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/persistence/UserBranchAccessRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/domain/Role.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/application/RoleService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/web/RoleDtos.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/web/RoleController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/RoleLifecycleIntegrationTest.java`

**Interfaces:**
- Consumes: `RoleService.get(UUID)`, `CurrentUser.require().companyId()`, `DefaultRole.all()`.
- Produces:
  - `UserBranchAccessRepository.existsByRoleId(UUID) : boolean`
  - `Role.setName(String)`
  - `RoleService.rename(UUID id, String name, String description) : Role`
  - `RoleService.delete(UUID id) : void`
  - DTO `UpdateRoleRequest(String name, String description)`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/vn/essvn/erpcafe/identity/RoleLifecycleIntegrationTest.java`:

```java
package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/** Role rename and delete, including the guards that keep the system usable. */
class RoleLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private String createRole() throws Exception {
        int n = SEQ.incrementAndGet();
        String json = mockMvc.perform(post("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"BARISTA%d","description":"Bar staff","permissions":["sales:read"]}
                                """.formatted(n)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String roleIdByName(String name) throws Exception {
        String json = mockMvc.perform(get("/api/v1/roles")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$[?(@.name == '%s')].id".formatted(name));
        return ids.get(0);
    }

    @Test
    void renameCustomRole_succeeds() throws Exception {
        String id = createRole();

        mockMvc.perform(put("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"BAR_LEAD\",\"description\":\"Lead barista\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BAR_LEAD"));
    }

    @Test
    void deleteUnusedCustomRole_succeeds() throws Exception {
        String id = createRole();

        mockMvc.perform(delete("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/roles/" + id)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void renamingADefaultRole_isRejected() throws Exception {
        mockMvc.perform(put("/api/v1/roles/" + roleIdByName("MANAGER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"SUPERVISOR\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletingADefaultRole_isRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/roles/" + roleIdByName("CASHIER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deletingARoleStillGrantedToSomeone_isRejected() throws Exception {
        mockMvc.perform(delete("/api/v1/roles/" + roleIdByName("OWNER"))
                        .header(HttpHeaders.AUTHORIZATION, authHeader(adminToken())))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*RoleLifecycleIntegrationTest*'`
Expected: FAIL with 405 on the PUT and DELETE routes.

- [ ] **Step 3: Add the grant-reference check and the name setter**

In `identity/persistence/UserBranchAccessRepository.java`:

```java
    boolean existsByRoleId(UUID roleId);
```

In `identity/domain/Role.java`, beside `getName`:

```java
    public void setName(String name) {
        this.name = name;
    }
```

- [ ] **Step 4: Add the service methods**

In `identity/application/RoleService.java`, add the import and the repository dependency:

```java
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
```

Add `UserBranchAccessRepository accessRepository` as a constructor parameter and field, then append:

```java
    /** Renames a custom role. Default roles keep their names: the startup reconciliation matches on them. */
    public Role rename(UUID roleId, String name, String description) {
        Role role = requireCustomRole(roleId);
        if (!role.getName().equals(name)
                && roleRepository.existsByCompanyIdAndName(role.getCompanyId(), name)) {
            throw new ConflictException("Role already exists: " + name);
        }
        role.setName(name);
        role.setDescription(description);
        return role;
    }

    /** Deletes a custom role that nobody currently holds. */
    public void delete(UUID roleId) {
        Role role = requireCustomRole(roleId);
        if (accessRepository.existsByRoleId(roleId)) {
            throw new BusinessRuleException(
                    "Cannot delete a role that is still granted to a user; revoke those grants first");
        }
        roleRepository.delete(role);
    }

    /**
     * Loads a role that the caller owns and that is not one of the seeded defaults.
     * The defaults are reconciled against {@link DefaultRole} on every startup and are
     * matched by name, so renaming or removing one would silently break that sync.
     */
    private Role requireCustomRole(UUID roleId) {
        Role role = get(roleId);
        if (!role.getCompanyId().equals(currentUser.require().companyId())) {
            throw new BusinessRuleException("Role belongs to a different company");
        }
        boolean isDefault = DefaultRole.all().stream()
                .anyMatch(d -> d.roleName().equals(role.getName()));
        if (isDefault) {
            throw new BusinessRuleException(
                    "The %s role is system-managed and cannot be renamed or deleted".formatted(role.getName()));
        }
        return role;
    }
```

- [ ] **Step 5: Add the DTO and endpoints**

In `identity/web/RoleDtos.java`:

```java
    public record UpdateRoleRequest(
            @NotBlank String name,
            String description) {
    }
```

In `identity/web/RoleController.java`, add the imports and handlers:

```java
import org.springframework.http.ResponseEntity;

import vn.essvn.erpcafe.identity.web.RoleDtos.UpdateRoleRequest;
```

```java
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public RoleResponse rename(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return roleMapper.toResponse(roleService.rename(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 6: Run tests, then the full suite, then commit**

Run: `cd backend && ./gradlew test --tests '*RoleLifecycleIntegrationTest*'` then `./gradlew test`
Expected: PASS. `RolePermissionIntegrationTest.editingOwnerRole_isRejected` still passes: `requireMutableRole` is unchanged and still guards the permission-editing paths.

```bash
git add backend/src/main/java/vn/essvn/erpcafe/identity backend/src/test/java/vn/essvn/erpcafe/identity
git commit -m "[Add] Role rename and delete with system-role and in-use guards"
```

---

### Task 3: Login throttling

An in-memory sliding window keyed by email **and** client address. Keying on both means an attacker cannot lock a known user out of their own account from a different address, which a bare email key would allow.

**Files:**
- Create: `backend/src/main/java/vn/essvn/erpcafe/common/exception/TooManyRequestsException.java`
- Create: `backend/src/main/java/vn/essvn/erpcafe/identity/security/LoginAttemptService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/common/web/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/application/AuthService.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/web/AuthController.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/LoginThrottleIntegrationTest.java`

**Interfaces:**
- Produces:
  - `TooManyRequestsException(String message)` mapped to 429
  - `LoginAttemptService.checkNotBlocked(String key)`, `recordFailure(String key)`, `reset(String key)`
  - `AuthService.login(String email, String rawPassword, String clientKey) : TokenPair` — the third parameter is the caller's address, used only for throttle keying

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/vn/essvn/erpcafe/identity/LoginThrottleIntegrationTest.java`:

```java
package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/** Repeated failed logins are throttled (AUTH-G11). */
class LoginThrottleIntegrationTest extends AbstractIntegrationTest {

    private int attemptLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void repeatedFailures_eventuallyReturn429() throws Exception {
        String email = "throttle-target@esscafe.vn";

        int lastStatus = 0;
        for (int i = 0; i < 15; i++) {
            lastStatus = attemptLogin(email, "definitely-wrong");
        }

        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    void aSuccessfulLogin_clearsTheCounter() throws Exception {
        // a few failures, then a success, then the counter must have reset
        for (int i = 0; i < 3; i++) {
            attemptLogin("admin@esscafe.vn", "wrong-password");
        }
        assertThat(attemptLogin("admin@esscafe.vn", "admin1234")).isEqualTo(200);
        assertThat(attemptLogin("admin@esscafe.vn", "wrong-password")).isEqualTo(422);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*LoginThrottleIntegrationTest*'`
Expected: FAIL. Every attempt returns 422, never 429.

- [ ] **Step 3: Add the exception and its mapping**

Create `common/exception/TooManyRequestsException.java`:

```java
package vn.essvn.erpcafe.common.exception;

/** Signals that a caller has exceeded a rate limit; rendered as HTTP 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
```

In `common/web/GlobalExceptionHandler.java`, add the import and handler:

```java
import vn.essvn.erpcafe.common.exception.TooManyRequestsException;
```

```java
    @ExceptionHandler(TooManyRequestsException.class)
    ProblemDetail handleTooManyRequests(TooManyRequestsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }
```

- [ ] **Step 4: Add the throttle**

Create `identity/security/LoginAttemptService.java`:

```java
package vn.essvn.erpcafe.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import vn.essvn.erpcafe.common.exception.TooManyRequestsException;

/**
 * Throttles repeated failed logins (AUTH-G11).
 *
 * <p>State is in-memory and per-instance: it resets on restart and is not shared
 * across replicas. That is adequate for the current single-instance deployment and
 * is deliberately cheap; move it to Redis when the app is scaled out.
 *
 * <p>Entries are keyed by email <em>and</em> client address. Keying on the email
 * alone would let anyone lock a known user out of their own account by failing
 * logins on their behalf.
 */
@Service
public class LoginAttemptService {

    private record Window(int failures, Instant firstFailureAt) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private final int maxFailures;
    private final Duration window;

    public LoginAttemptService(
            @Value("${app.security.login.max-failures:10}") int maxFailures,
            @Value("${app.security.login.window:PT15M}") Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Throws if the key is currently blocked. Called before the password is checked. */
    public void checkNotBlocked(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return;
        }
        if (Instant.now().isAfter(current.firstFailureAt().plus(window))) {
            windows.remove(key);
            return;
        }
        if (current.failures() >= maxFailures) {
            throw new TooManyRequestsException(
                    "Too many failed login attempts. Try again later.");
        }
    }

    /** Records one failed attempt, starting a fresh window if the previous one lapsed. */
    public void recordFailure(String key) {
        Instant now = Instant.now();
        windows.compute(key, (k, existing) -> {
            if (existing == null || now.isAfter(existing.firstFailureAt().plus(window))) {
                return new Window(1, now);
            }
            return new Window(existing.failures() + 1, existing.firstFailureAt());
        });
    }

    /** Clears the counter after a successful login. */
    public void reset(String key) {
        windows.remove(key);
    }
}
```

- [ ] **Step 5: Wire it into the login path**

In `identity/application/AuthService.java`, add `LoginAttemptService loginAttemptService` as a constructor parameter and field, then replace `login` with:

```java
    /**
     * Verifies credentials and issues a token pair. {@code clientKey} is the caller's
     * address; it only ever feeds the failed-attempt throttle.
     */
    public TokenPair login(String email, String rawPassword, String clientKey) {
        String throttleKey = email + "|" + clientKey;
        loginAttemptService.checkNotBlocked(throttleKey);

        User user = userRepository.findByEmail(email)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            loginAttemptService.recordFailure(throttleKey);
            throw new BusinessRuleException("Invalid email or password");
        }
        if (!user.isActive()) {
            loginAttemptService.recordFailure(throttleKey);
            throw new BusinessRuleException("Account is disabled");
        }

        loginAttemptService.reset(throttleKey);
        return issueTokens(user);
    }
```

In `identity/web/AuthController.java`, add the import and pass the address through:

```java
import jakarta.servlet.http.HttpServletRequest;
```

```java
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        TokenPair tokens = authService.login(request.email(), request.password(), http.getRemoteAddr());
        return TokenResponse.bearer(tokens.accessToken(), tokens.refreshToken());
    }
```

- [ ] **Step 6: Run tests, then the full suite, then commit**

Run: `cd backend && ./gradlew test --tests '*LoginThrottleIntegrationTest*'` then `./gradlew test`
Expected: PASS. `AuthFlowIntegrationTest.login_withWrongPassword_returns422` still passes: it makes a single failed attempt, well under the threshold.

```bash
git add backend/src/main/java/vn/essvn/erpcafe backend/src/test/java/vn/essvn/erpcafe/identity
git commit -m "[Add] Throttle repeated failed logins per email and address"
```

---

### Task 4: Refresh-token families and reuse detection

**Files:**
- Create: `backend/src/main/resources/db/migration/V10_001__add_refresh_token_family.sql`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/domain/RefreshToken.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/persistence/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/vn/essvn/erpcafe/identity/application/AuthService.java`
- Test: `backend/src/test/java/vn/essvn/erpcafe/identity/RefreshTokenReuseIntegrationTest.java`

**Interfaces:**
- Produces:
  - `RefreshToken(UUID userId, String tokenHash, Instant expiresAt, UUID familyId)` — the constructor gains a fourth parameter
  - `RefreshToken.getFamilyId() : UUID`
  - `RefreshTokenRepository.findByFamilyIdAndRevokedFalse(UUID) : List<RefreshToken>`

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/vn/essvn/erpcafe/identity/RefreshTokenReuseIntegrationTest.java`:

```java
package vn.essvn.erpcafe.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Refresh-token reuse detection (AUTH-G10). Rotation alone only rejects the replayed
 * token; a stolen token must invalidate the whole descendant family.
 */
class RefreshTokenReuseIntegrationTest extends AbstractIntegrationTest {

    private String refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void replayingARotatedToken_revokesTheWholeFamily() throws Exception {
        String first = JsonPath.read(loginBody("admin@esscafe.vn", "admin1234"), "$.refreshToken");

        // legitimate client rotates once; `second` is now the live token
        String second = JsonPath.read(refresh(first), "$.refreshToken");

        // attacker replays the stolen, already-rotated token
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(first)))
                .andExpect(status().isUnprocessableEntity());

        // the legitimate client's live token must now be dead too
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(second)))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*RefreshTokenReuseIntegrationTest*'`
Expected: FAIL on the last assertion. The replay is rejected, but `second` still refreshes successfully with 200.

- [ ] **Step 3: Add the migration**

Create `src/main/resources/db/migration/V10_001__add_refresh_token_family.sql`:

```sql
-- Refresh-token families (AUTH-G10). Every token minted by rotating an existing one
-- inherits its family; detecting a replayed token revokes the whole family, which is
-- the signal that a token was stolen.

ALTER TABLE refresh_token ADD COLUMN family_id UUID;

-- Existing tokens each become their own single-member family.
UPDATE refresh_token SET family_id = id WHERE family_id IS NULL;

ALTER TABLE refresh_token ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_family_id ON refresh_token (family_id);
```

- [ ] **Step 4: Add the family to the entity**

In `identity/domain/RefreshToken.java`, add the field, constructor parameter, and getter:

```java
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
```

```java
    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt, UUID familyId) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
    }

    public UUID getFamilyId() {
        return familyId;
    }
```

In `identity/persistence/RefreshTokenRepository.java`:

```java
    List<RefreshToken> findByFamilyIdAndRevokedFalse(UUID familyId);
```

- [ ] **Step 5: Thread the family through AuthService**

In `identity/application/AuthService.java`, replace `refresh` and `issueTokens`, and add the helper:

```java
    public TokenPair refresh(String rawRefreshToken) {
        // We store only the SHA-256 hash, so look up by hash — a DB leak never exposes usable tokens.
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessRuleException("Invalid refresh token"));

        // Reuse detection: this token was already rotated away, so either it was stolen and
        // replayed, or the legitimate holder's copy was. Either way the family is compromised —
        // revoke every live token in it and force a fresh login.
        if (stored.isRevoked()) {
            revokeFamily(stored.getFamilyId());
            throw new BusinessRuleException(
                    "Refresh token has already been used; all sessions have been revoked");
        }
        if (!stored.isActive(Instant.now())) {
            throw new BusinessRuleException("Refresh token expired or revoked");
        }

        // Rotation: each refresh token is single-use.
        stored.revoke();
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BusinessRuleException("User no longer exists"));
        if (!user.isActive()) {
            throw new BusinessRuleException("Account is disabled");
        }
        return issueTokens(user, stored.getFamilyId());
    }
```

```java
    /** A login starts a brand-new token family. */
    private TokenPair issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        AuthPrincipal principal = buildPrincipal(user);
        // Access token: stateless JWT, short-lived, carries permissions/branches (no DB lookup per request).
        String accessToken = jwtService.generateAccessToken(principal);
        // Refresh token: opaque random string returned to the client; only its hash is persisted.
        String rawRefresh = randomToken();
        Instant expiry = Instant.now().plus(securityProperties.jwt().refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user.getId(), sha256(rawRefresh), expiry, familyId));
        return new TokenPair(accessToken, rawRefresh);
    }

    private void revokeFamily(UUID familyId) {
        refreshTokenRepository.findByFamilyIdAndRevokedFalse(familyId).forEach(RefreshToken::revoke);
    }
```

- [ ] **Step 6: Run tests, then the full suite, then commit**

Run: `cd backend && ./gradlew test --tests '*RefreshTokenReuseIntegrationTest*'` then `./gradlew test`
Expected: PASS. `AuthFlowIntegrationTest.refreshToken_rotatesAndLogoutRevokes` still passes: it rotates once and then logs out, never replaying a rotated token.

```bash
git add backend/src/main/java/vn/essvn/erpcafe/identity backend/src/main/resources/db/migration backend/src/test/java/vn/essvn/erpcafe/identity
git commit -m "[Add] Refresh-token families with reuse detection"
```

---

### Task 5: Spec truth pass

**Files:**
- Modify: `doc/erp-cafe-auth-spec.md`

- [ ] **Step 1: Rewrite the gap table**

In §9, replace the table with one that reflects the code. Mark AUTH-G01 through AUTH-G06 as fixed with the test that proves each, add the three gaps the spec never tracked, and mark the ones this plan closes.

Fixed and proven:
- G01 `UserAccessGrantIntegrationTest.grantAccess_rejectsBranchFromAnotherCompany`
- G02, G03 `UserTenantIsolationIntegrationTest`
- G04 `StaffTenantIsolationTest`, `OutletReportTenantIsolationTest`
- G05 `BranchContextFilter` deleted; `JwtAuthenticationFilter` is the sole `BranchContext` writer
- G06 `DefaultRolePermissionSyncTest` — note the method is `syncDefaultRolePermissions`, not `syncOwnerPermissions`, and it reconciles all default roles add-only
- G10 `RefreshTokenReuseIntegrationTest` (this plan)
- G11 `LoginThrottleIntegrationTest` (this plan)
- G12 `AccountLifecycleIntegrationTest` (this plan) — password change and admin reset now exist; a self-service forgotten-password email flow does not, and remains open

New rows to add:
- **AUTH-G15 — Organization module was unscoped.** Company and branch list, get, update and delete resolved without a tenant check; cashiers hold `branch:read`. Fixed by the organization tenant scoping plan, proven by `OrganizationTenantIsolationTest`.
- **AUTH-G16 — No account lifecycle.** Disabled status and the password setter existed but nothing ever called them, so an account could not be disabled and a password could not be changed. Fixed in this plan.
- **AUTH-G17 — No role lifecycle.** Roles could be created but never renamed or deleted. Fixed in this plan.

Still open after this plan: G07 (per-branch permission evaluation), G08 (revocation lag, now narrowed to the access-token lifetime for status and password changes), G09 (static HS256 secret), G13 (globally unique email), G14 (no auth audit trail).

- [ ] **Step 2: Update §2 "Current state" and §6 flows**

Add the new endpoints to the flow list: `PUT /users/{id}`, `PUT /users/{id}/status`, `POST /users/me/password`, `PUT /users/{id}/password`, `PUT /roles/{id}`, `DELETE /roles/{id}`. Note the login throttle in the AUTH-101 flow and the family revocation in AUTH-103.

- [ ] **Step 3: Commit**

```bash
git add doc/erp-cafe-auth-spec.md
git commit -m "[Docs] Auth spec truth pass after lifecycle and hardening work"
```

---

## Deferred, deliberately out of scope

- **AUTH-G07, per-branch permission evaluation.** Needs company-wide grants first, via a nullable `branch_id` on `user_branch_access`. Otherwise an owner cannot grant themselves access at a newly created branch. Changes the JWT claim shape, so it is its own project.
- **AUTH-G09, key rotation.** Wants a key id in the JWT header and a small keyring. Worth doing with, or instead of, an external identity provider.
- **AUTH-G13, per-company email uniqueness.** Changing `uk_user_email` to `(company_id, email)` means login needs a tenant discriminator, such as a company code or a subdomain. A product decision.
- **AUTH-G14, authentication audit trail.** Best built on the Modulith event registry that ADR-0004 already commits to, so the events externalize unchanged later.
