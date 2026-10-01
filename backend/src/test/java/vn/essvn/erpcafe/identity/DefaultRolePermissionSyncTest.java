package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vn.essvn.erpcafe.bootstrap.DataInitializer;
import vn.essvn.erpcafe.identity.domain.DefaultRole;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.persistence.PermissionRepository;
import vn.essvn.erpcafe.identity.persistence.RoleRepository;
import vn.essvn.erpcafe.identity.persistence.UserRepository;
import vn.essvn.erpcafe.support.AbstractIntegrationTest;

/**
 * Class: DefaultRolePermissionSyncTest
 * Purpose: Proves default roles gain only permissions new to the catalog, so an owner's removal survives a restart.
 */
class DefaultRolePermissionSyncTest extends AbstractIntegrationTest {

    @Autowired
    private DataInitializer dataInitializer;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    private Role role(String name) {
        UUID companyId = userRepository.findByEmail("admin@esscafe.vn").orElseThrow().getCompanyId();
        return roleRepository.findByCompanyIdAndName(companyId, name).orElseThrow();
    }

    private Set<String> names(Role role) {
        return role.getPermissions().stream().map(Permission::getName).collect(Collectors.toSet());
    }

    /**
     * Other test classes log in as these roles, so each test puts them back exactly as seeded, and
     * no role is left synced past the real catalog (the bumped-catalog test syncs every company's).
     */
    @AfterEach
    void restoreDefaultRoles() {
        for (Role role : roleRepository.findAll()) {
            if (role.getSyncedCatalogVersion() > Permissions.CATALOG_VERSION) {
                role.setSyncedCatalogVersion(Permissions.CATALOG_VERSION);
                roleRepository.save(role);
            }
        }
        for (DefaultRole def : DefaultRole.all()) {
            Role role = role(def.roleName());
            role.setPermissions(def.permissions().stream()
                    .map(name -> permissionRepository.findByName(name).orElseThrow())
                    .collect(Collectors.toSet()));
            role.setSyncedCatalogVersion(Permissions.CATALOG_VERSION);
            roleRepository.save(role);
        }
    }

    @Test
    void removedDefaultPermission_staysRemovedAfterSync() {
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.getPermissions().removeIf(p -> Permissions.USER_WRITE.equals(p.getName()));
        roleRepository.save(manager);

        dataInitializer.run(null);

        assertThat(names(role(DefaultRole.MANAGER.roleName()))).doesNotContain(Permissions.USER_WRITE);
    }

    @Test
    void permissionNewToTheCatalog_isGrantedOnce() {
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.getPermissions().removeIf(p -> Permissions.USER_WRITE.equals(p.getName())
                || Permissions.REPORT_READ.equals(p.getName()));
        roleRepository.save(manager);
        // A release that introduces report:read: it moves to the next catalog version.
        int next = Permissions.CATALOG_VERSION + 1;
        Map<String, Integer> since = new HashMap<>(Permissions.SINCE);
        since.put(Permissions.REPORT_READ, next);

        dataInitializer.syncDefaultRolePermissions(next, since);

        Role synced = role(DefaultRole.MANAGER.roleName());
        assertThat(names(synced)).contains(Permissions.REPORT_READ).doesNotContain(Permissions.USER_WRITE);
        assertThat(synced.getSyncedCatalogVersion()).isEqualTo(next);
    }

    @Test
    void roleNeverSynced_receivesItsWholeDefinition() {
        // Version 0 means never synced: seedTenant creates default roles that way and the same
        // run() syncs them at once, so such a role is offered its whole definition.
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.getPermissions().removeIf(p -> Permissions.REPORT_READ.equals(p.getName()));
        manager.setSyncedCatalogVersion(0);
        roleRepository.save(manager);

        dataInitializer.run(null);

        Role synced = role(DefaultRole.MANAGER.roleName());
        assertThat(names(synced)).containsAll(DefaultRole.MANAGER.permissions());
        assertThat(synced.getSyncedCatalogVersion()).isEqualTo(Permissions.CATALOG_VERSION);
    }

    @Test
    void olderCatalog_neverLowersTheSyncedVersion() {
        // As after a rollback: the role was synced by a newer release than the one now starting.
        int newer = Permissions.CATALOG_VERSION + 1;
        Role manager = role(DefaultRole.MANAGER.roleName());
        manager.setSyncedCatalogVersion(newer);
        roleRepository.save(manager);

        dataInitializer.run(null);

        assertThat(role(DefaultRole.MANAGER.roleName()).getSyncedCatalogVersion()).isEqualTo(newer);
    }

    @Test
    void catalog_datesEveryPermission() {
        assertThat(Permissions.SINCE.keySet()).isEqualTo(Permissions.CATALOG.keySet());
        assertThat(Permissions.SINCE.values()).allMatch(v -> v >= 1 && v <= Permissions.CATALOG_VERSION);
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
