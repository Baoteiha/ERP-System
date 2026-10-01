package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.identity.domain.AccessPolicy;
import vn.essvn.erpcafe.identity.domain.Permission;
import vn.essvn.erpcafe.identity.domain.Permissions;
import vn.essvn.erpcafe.identity.domain.Role;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;

/**
 * Class: AccessPolicyTest
 * Purpose: Pins the grant, manage and edit-role rules to the actor's own permissions and branches, with OWNER exempt.
 */
class AccessPolicyTest {

    private static final UUID HELD_BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    private static AuthPrincipal actor(String roleName, String... permissions) {
        return new AuthPrincipal(UUID.randomUUID(), "actor@test", UUID.randomUUID(),
                Set.of(HELD_BRANCH), Set.of(permissions), Set.of(roleName));
    }

    private static AuthPrincipal manager() {
        return actor("MANAGER", Permissions.USER_WRITE, Permissions.SALES_READ, Permissions.SALES_WRITE);
    }

    private static AuthPrincipal owner() {
        return actor("OWNER", Permissions.USER_WRITE);
    }

    private static Role role(String... permissions) {
        Role role = new Role(UUID.randomUUID(), "SOME_ROLE", "test");
        role.setPermissions(Arrays.stream(permissions)
                .map(name -> new Permission(name, name))
                .collect(Collectors.toSet()));
        return role;
    }

    @Test
    void grant_allowsARoleWithinTheActorsPermissionsAtAHeldBranch() {
        assertThatCode(() -> AccessPolicy.requireCanGrant(manager(), role(Permissions.SALES_READ), HELD_BRANCH))
                .doesNotThrowAnyException();
    }

    @Test
    void grant_refusesARoleWithAPermissionTheActorLacks() {
        Role wider = role(Permissions.SALES_READ, Permissions.COMPANY_WRITE);

        assertThatThrownBy(() -> AccessPolicy.requireCanGrant(manager(), wider, HELD_BRANCH))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void grant_refusesABranchTheActorDoesNotHold() {
        assertThatThrownBy(() -> AccessPolicy.requireCanGrant(manager(), role(Permissions.SALES_READ), OTHER_BRANCH))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void manage_allowsATargetWithinTheActorsPermissions() {
        assertThatCode(() -> AccessPolicy.requireCanManage(manager(), Set.of(Permissions.SALES_READ)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AccessPolicy.requireCanManage(manager(), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void manage_refusesATargetHoldingMoreThanTheActor() {
        Set<String> target = Set.of(Permissions.SALES_READ, Permissions.ROLE_WRITE);

        assertThatThrownBy(() -> AccessPolicy.requireCanManage(manager(), target))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editRole_allowsOnlyPermissionsTheActorHolds() {
        assertThatCode(() -> AccessPolicy.requireCanEditRole(manager(), Set.of(Permissions.SALES_WRITE)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireCanEditRole(manager(), Set.of(Permissions.COMPANY_WRITE)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void owner_passesEveryRule() {
        Set<String> everything = Set.copyOf(Permissions.ALL);

        assertThatCode(() -> {
            AccessPolicy.requireCanGrant(owner(), role(Permissions.ALL.toArray(String[]::new)), OTHER_BRANCH);
            AccessPolicy.requireCanManage(owner(), everything);
            AccessPolicy.requireCanEditRole(owner(), everything);
        }).doesNotThrowAnyException();
    }
}
