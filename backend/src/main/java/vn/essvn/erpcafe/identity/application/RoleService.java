package vn.essvn.erpcafe.identity.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserBranchAccessRepository;
import vn.essvn.erpcafe.identity.security.CurrentUser;

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

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository,
            UserBranchAccessRepository accessRepository, CurrentUser currentUser) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.accessRepository = accessRepository;
        this.currentUser = currentUser;
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
        Role role = new Role(companyId, name, description);
        role.setPermissions(resolvePermissions(permissionNames));
        return roleRepository.save(role);
    }

    /** Replaces a role's entire permission set. */
    public Role replacePermissions(UUID roleId, Set<String> permissionNames) {
        Role role = requireMutableRole(roleId);
        role.setPermissions(resolvePermissions(permissionNames));
        return role;
    }

    /** Adds permissions to a role (union; existing permissions are kept). */
    public Role addPermissions(UUID roleId, Set<String> permissionNames) {
        Role role = requireMutableRole(roleId);
        role.getPermissions().addAll(resolvePermissions(permissionNames));
        return role;
    }

    /** Removes a single permission from a role. */
    public Role removePermission(UUID roleId, String permissionName) {
        Role role = requireMutableRole(roleId);
        boolean removed = role.getPermissions().removeIf(p -> p.getName().equals(permissionName));
        if (!removed) {
            throw new ResourceNotFoundException(
                    "Role does not have permission: " + permissionName);
        }
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

    /** Loads a role, enforcing it belongs to the caller's company and is not the system-managed OWNER. */
    private Role requireMutableRole(UUID roleId) {
        Role role = get(roleId);
        if (DefaultRole.OWNER.roleName().equals(role.getName())) {
            throw new BusinessRuleException("The OWNER role is system-managed and cannot be edited");
        }
        return role;
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
