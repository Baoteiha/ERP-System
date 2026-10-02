package vn.essvn.erpcafe.identity.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.common.persistence.SystemTransactions;
import vn.essvn.erpcafe.identity.domain.AccessPolicy;
import vn.essvn.erpcafe.identity.domain.HeldAccess;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.RefreshToken;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.User;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.domain.UserStatus;
import vn.essvn.erpcafe.identity.persistence.RefreshTokenRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.identity.security.UserGrantCache;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * User management: creating accounts and granting per-branch role access.
 * New users belong to the acting user's company; branch grants are validated
 * against the organization module via {@link OrganizationApi}.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final UserBranchAccessRepository accessRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final OrganizationApi organizationApi;
    private final CurrentUser currentUser;
    private final UserGrantCache grantCache;
    private final AuthVersions authVersions;
    private final SystemTransactions system;

    UserService(UserRepository userRepository, UserBranchAccessRepository accessRepository,
            RoleRepository roleRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, OrganizationApi organizationApi, CurrentUser currentUser,
            UserGrantCache grantCache, AuthVersions authVersions, SystemTransactions system) {
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.organizationApi = organizationApi;
        this.currentUser = currentUser;
        this.grantCache = grantCache;
        this.authVersions = authVersions;
        this.system = system;
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }

    /** A new account holds no permissions until {@link #grantAccess}, which is where the policy applies. */
    public User create(String rawEmail, String rawPassword, String fullName) {
        String email = User.normalizeEmail(rawEmail);
        if (emailTaken(email)) {
            throw new ConflictException("Email already exists: " + email);
        }
        User user = new User(email, passwordEncoder.encode(rawPassword), fullName);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<UserBranchAccess> accessOf(UUID userId) {
        get(userId);
        return accessRepository.findByUserId(userId);
    }

    /**
     * Grants (or updates) a user's role at a branch. The actor must be able to manage the user,
     * since replacing a grant can demote them, and to grant the role at that branch.
     */
    public UserBranchAccess grantAccess(UUID userId, UUID branchId, UUID roleId) {
        User user = get(userId);
        // The branch must belong to the user's own company. A bare existence check would
        // let a caller attach another company's branch to the user's access set, which
        // every branch-scoped query downstream then trusts (cross-tenant escalation).
        if (!organizationApi.branchExistsInCompany(branchId, user.getCompanyId())) {
            throw new ResourceNotFoundException("Branch not found or inactive: " + branchId);
        }
        // locked first: a concurrent widening of the role waits for this grant, or this grant sees it
        roleRepository.lockByIdAndCompanyId(roleId, user.getCompanyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Role", roleId));
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Role", roleId));
        requireCanManage(userId);
        AccessPolicy.requireCanGrant(currentUser.require(), role, branchId);
        grantCache.evictAfterCommit(userId);
        return accessRepository.findByUserIdAndBranchId(userId, branchId)
                .map(existing -> {
                    existing.setRoleId(roleId);
                    return existing;
                })
                .orElseGet(() -> accessRepository.save(new UserBranchAccess(userId, branchId, roleId)));
    }

    /**
     * Revokes a user's access at a branch. Revoking is a grant undone, so it takes what granting
     * it would: a user the actor can manage, and a branch the actor holds.
     */
    public void revokeAccess(UUID userId, UUID branchId) {
        get(userId);
        UserBranchAccess access = accessRepository.findByUserIdAndBranchId(userId, branchId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User " + userId + " has no access at branch " + branchId));
        requireCanManage(userId);
        Role role = roleRepository.findById(access.getRoleId())
                .orElseThrow(() -> ResourceNotFoundException.of("Role", access.getRoleId()));
        AccessPolicy.requireCanGrant(currentUser.require(), role, branchId);
        accessRepository.delete(access);
        grantCache.evictAfterCommit(userId);
    }

    // --- account lifecycle ---

    /** Updates a user's display name and login email. */
    public User updateProfile(UUID id, String rawEmail, String fullName) {
        String email = User.normalizeEmail(rawEmail);
        User user = get(id);
        requireCanManage(id);
        if (!user.getEmail().equals(email) && emailTaken(email)) {
            throw new ConflictException("Email already exists: " + email);
        }
        user.setEmail(email);
        user.setFullName(fullName);
        return user;
    }

    /**
     * Enables or disables an account. Either way the access tokens the user holds end; disabling
     * also revokes every outstanding refresh token, so the account cannot sign back in by refreshing.
     *
     * <p>Changing your own status is refused: disabling yourself is the one change
     * you could not then undo.
     */
    public User changeStatus(UUID id, UserStatus status) {
        if (id.equals(currentUser.require().userId())) {
            throw new BusinessRuleException("You cannot change your own account status");
        }
        User user = get(id);
        requireCanManage(id);
        user.setStatus(status);
        if (status == UserStatus.DISABLED) {
            revokeAllTokens(id);
        }
        authVersions.bump(user.getCompanyId(), List.of(id));
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
        authVersions.bump(user.getCompanyId(), List.of(userId));
    }

    /** Administrative reset; does not need the old password, but does need user:write. */
    public void resetPassword(UUID id, String newPassword) {
        User user = get(id);
        requireCanManage(id);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        revokeAllTokens(user.getId());
        authVersions.bump(user.getCompanyId(), List.of(user.getId()));
    }

    // Emails are unique across companies (login finds a user by email alone), so the check runs as
    // the system: the tenant's own connection would see only its company's users.
    private boolean emailTaken(String email) {
        return system.run(() -> userRepository.existsByEmailInAnyCompany(email));
    }

    /** Refuses with 403 unless the acting user may manage the target user (see {@link AccessPolicy}). */
    private void requireCanManage(UUID userId) {
        AccessPolicy.requireCanManage(currentUser.require(), heldAccessOf(userId));
    }

    /** The permissions of the user's role at each of their branches that is still live, and those roles' names. */
    private HeldAccess heldAccessOf(UUID userId) {
        Set<UUID> liveBranchIds = organizationApi.listActiveBranches(TenantContext.require()).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        List<UserBranchAccess> grants = accessRepository.findByUserId(userId).stream()
                .filter(g -> liveBranchIds.contains(g.getBranchId()))
                .toList();
        Map<UUID, Role> roles = roleRepository.findAllById(
                grants.stream().map(UserBranchAccess::getRoleId).distinct().toList()).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<UUID, Set<String>> permissionsByBranch = grants.stream().collect(Collectors.toMap(
                UserBranchAccess::getBranchId,
                g -> roles.get(g.getRoleId()).getPermissions().stream()
                        .map(Permission::getName).collect(Collectors.toSet())));
        return new HeldAccess(permissionsByBranch,
                roles.values().stream().map(Role::getName).collect(Collectors.toSet()));
    }

    /** Revokes every outstanding refresh token for a user. */
    private void revokeAllTokens(UUID userId) {
        refreshTokenRepository.findByUserIdAndRevokedFalse(userId).forEach(RefreshToken::revoke);
    }
}
