package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vn.essvn.erpcafe.bootstrap.DataInitializer;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.organization.persistence.CompanyRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Startup reconciliation of default-role permissions (AUTH-G06, plan task A6).
 *
 * <p>When a release adds permissions to the catalog, every default role must
 * receive the ones its {@link DefaultRole} definition lists — not only OWNER.
 * Otherwise an upgraded deployment's managers 403 on every new endpoint. The
 * sync is add-only: permissions an admin granted beyond the definition are
 * never revoked.
 */
class DefaultRolePermissionSyncTest extends AbstractIntegrationTest {

    @Autowired
    private DataInitializer dataInitializer;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private CompanyRepository companyRepository;

    private Role role(String name) {
        var companyId = companyRepository.findAll().get(0).getId();
        return roleRepository.findByCompanyIdAndName(companyId, name).orElseThrow();
    }

    private Set<String> names(Role role) {
        return role.getPermissions().stream().map(Permission::getName).collect(Collectors.toSet());
    }

    @Test
    void managerRole_regainsAMissingDefaultPermissionOnStartup() {
        // Simulate a pre-upgrade database: MANAGER exists without a permission its
        // definition now includes (as happened when staff/report permissions landed).
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.getPermissions().removeIf(p -> Permissions.REPORT_READ.equals(p.getName()));
        roleRepository.save(manager);
        assertThat(names(role(DefaultRole.MANAGER.roleName()))).doesNotContain(Permissions.REPORT_READ);

        dataInitializer.run(null);

        assertThat(names(role(DefaultRole.MANAGER.roleName())))
                .containsAll(DefaultRole.MANAGER.permissions());
    }

    @Test
    void sync_isAddOnly_customGrantsSurvive() {
        // An admin deliberately granted CASHIER something beyond its definition.
        Role cashier = role(DefaultRole.CASHIER.roleName());
        Permission extra = permissionRepository.findByName(Permissions.COMPANY_WRITE).orElseThrow();
        cashier.getPermissions().add(extra);
        roleRepository.save(cashier);

        dataInitializer.run(null);

        assertThat(names(role(DefaultRole.CASHIER.roleName())))
                .contains(Permissions.COMPANY_WRITE)
                .containsAll(DefaultRole.CASHIER.permissions());
    }
}
