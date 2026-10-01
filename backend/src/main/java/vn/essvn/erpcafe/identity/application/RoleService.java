package vn.essvn.erpcafe.identity.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.domain.AccessPolicy;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.identity.security.RolePermissionCache;
import vn.essvn.erpcafe.organization.api.BranchDto;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Role management within the acting user's company: listing/creating roles and
 * managing a role's permission set (replace / add / remove).
 */
@Service
@Transactional
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserBranchAccessRepository accessRepository;
    private final CurrentUser currentUser;
    private final RolePermissionCache roleCache;
    private final OrganizationApi organizationApi;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository,
            UserBranchAccessRepository accessRepository, CurrentUser currentUser, RolePermissionCache roleCache,
            OrganizationApi organizationApi) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.accessRepository = accessRepository;
        this.currentUser = currentUser;
        this.roleCache = roleCache;
        this.organizationApi = organizationApi;
    }

    @Transactional(readOnly = true)
    public List<Role> listForCurrentCompany() {
        return roleRepository.findByCompanyId(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Role get(UUID id) {
        return roleRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Role", id));
    }

    public Role create(String name, String description, Set<String> permissionNames) {
        UUID companyId = currentUser.require().companyId();
        if (roleRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Role already exists: " + name);
        }
        Set<Permission> permissions = resolvePermissions(permissionNames);
        AccessPolicy.requireCanEditRole(currentUser.require(), permissionNames);
        Role role = new Role(companyId, name, description);
        role.setPermissions(permissions);
        return roleRepository.save(role);
    }

    /** Replaces a role's entire permission set. */
    public Role replacePermissions(UUID roleId, Set<String> permissionNames) {
        Role role = requireMutableRole(roleId);
        Set<Permission> permissions = resolvePermissions(permissionNames);
        requireCanEdit(role, permissionNames);
        role.setPermissions(permissions);
        roleCache.evictAfterCommit(roleId);
        return role;
    }

    /** Adds permissions to a role (union; existing permissions are kept). */
    public Role addPermissions(UUID roleId, Set<String> permissionNames) {
        Role role = requireMutableRole(roleId);
        Set<Permission> permissions = resolvePermissions(permissionNames);
        requireCanEdit(role, permissionNames);
        role.getPermissions().addAll(permissions);
        roleCache.evictAfterCommit(roleId);
        return role;
    }

    /** Removes a single permission from a role. */
    public Role removePermission(UUID roleId, String permissionName) {
        Role role = requireMutableRole(roleId);
        requireCanEdit(role, Set.of());
        boolean removed = role.getPermissions().removeIf(p -> p.getName().equals(permissionName));
        if (!removed) {
            throw new ResourceNotFoundException(
                    "Role does not have permission: " + permissionName);
        }
        roleCache.evictAfterCommit(roleId);
        return role;
    }

    /** Renames a custom role. Default roles keep their names: the startup sync matches on them. */
    public Role rename(UUID roleId, String name, String description) {
        Role role = requireCustomRole(roleId);
        if (!role.getName().equals(name)
                && roleRepository.existsByCompanyIdAndName(role.getCompanyId(), name)) {
            throw new ConflictException("Role already exists: " + name);
        }
        role.setName(name);
        role.setDescription(description);
        roleCache.evictAfterCommit(roleId);
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
        roleCache.evictAfterCommit(roleId);
    }

    /**
     * Loads a role the caller owns that is not one of the seeded defaults.
     *
     * <p>The defaults are reconciled against {@link DefaultRole} on every startup and
     * are matched <em>by name</em>, so renaming or deleting one would silently break
     * that sync and leave the company without a role it is expected to have.
     */
    private Role requireCustomRole(UUID roleId) {
        Role role = get(roleId);
        boolean isDefault = DefaultRole.all().stream()
                .anyMatch(d -> d.roleName().equals(role.getName()));
        if (isDefault) {
            throw new BusinessRuleException(
                    "The %s role is system-managed and cannot be renamed or deleted".formatted(role.getName()));
        }
        return role;
    }

    /**
     * Loads a role, enforcing it belongs to the caller's company and is not the system-managed OWNER.
     * The row is locked first, so a concurrent grant of the role either waits for this change or is seen by it.
     */
    private Role requireMutableRole(UUID roleId) {
        roleRepository.lockByIdAndCompanyId(roleId, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Role", roleId));
        Role role = get(roleId);
        if (DefaultRole.OWNER.roleName().equals(role.getName())) {
            throw new BusinessRuleException("The OWNER role is system-managed and cannot be edited");
        }
        return role;
    }

    /**
     * Refuses with 403 unless the actor holds every permission the role has now and every one
     * the edit gives it. A role's permissions are what everyone holding it can do, so the actor
     * may neither widen it past their own nor reshape one that already exceeds them. What the edit
     * adds must also be the actor's at every live branch where the role is granted.
     */
    private void requireCanEdit(Role role, Set<String> newPermissions) {
        AuthPrincipal actor = currentUser.require();
        Set<String> current = new HashSet<>();
        role.getPermissions().forEach(p -> current.add(p.getName()));
        Set<String> touched = new HashSet<>(newPermissions);
        touched.addAll(current);
        AccessPolicy.requireCanEditRole(actor, touched);

        Set<String> added = new HashSet<>(newPermissions);
        added.removeAll(current);
        if (!added.isEmpty()) {
            AccessPolicy.requireCanWidenRole(actor, added, liveBranchesGranting(role));
        }
    }

    private Set<UUID> liveBranchesGranting(Role role) {
        Set<UUID> live = organizationApi.listActiveBranches(role.getCompanyId()).stream()
                .map(BranchDto::id).collect(Collectors.toSet());
        return accessRepository.findByRoleId(role.getId()).stream()
                .map(UserBranchAccess::getBranchId)
                .filter(live::contains)
                .collect(Collectors.toSet());
    }

    private Set<Permission> resolvePermissions(Set<String> names) {
        Set<Permission> resolved = new HashSet<>();
        for (String name : names) {
            resolved.add(permissionRepository.findByName(name)
                    .orElseThrow(() -> new BusinessRuleException("Unknown permission: " + name)));
        }
        return resolved;
    }
}
