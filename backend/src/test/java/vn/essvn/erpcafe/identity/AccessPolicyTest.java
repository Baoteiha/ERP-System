package vn.essvn.erpcafe.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.identity.domain.AccessPolicy;
import vn.essvn.erpcafe.identity.domain.HeldAccess;
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
                Map.of(HELD_BRANCH, Set.of(permissions)), Set.of(permissions), Set.of(roleName));
    }

    /** A manager at the held branch who is only a cashier at the other one. */
    private static AuthPrincipal managerHereCashierThere() {
        return new AuthPrincipal(UUID.randomUUID(), "actor@test", UUID.randomUUID(),
                Map.of(HELD_BRANCH, Set.of(Permissions.USER_WRITE, Permissions.SALES_WRITE, Permissions.SALES_REFUND),
                        OTHER_BRANCH, Set.of(Permissions.SALES_WRITE)),
                Set.of(Permissions.SALES_WRITE), Set.of("MANAGER", "CASHIER"));
    }

    private static HeldAccess held(UUID branchId, String roleName, String... permissions) {
        return new HeldAccess(Map.of(branchId, Set.of(permissions)), Set.of(roleName));
    }

    private static AuthPrincipal manager() {
        return actor("MANAGER", Permissions.USER_WRITE, Permissions.SALES_READ, Permissions.SALES_WRITE);
    }

    private static AuthPrincipal owner() {
        return actor("OWNER", Permissions.USER_WRITE);
    }

    private static Role role(String... permissions) {
        return named("SOME_ROLE", permissions);
    }

    private static Role named(String name, String... permissions) {
        Role role = new Role(UUID.randomUUID(), name, "test");
        role.setPermissions(Arrays.stream(permissions)
                .map(permission -> new Permission(permission, permission))
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
    void grant_refusesTheOwnerRoleToANonOwnerHoldingEveryPermission() {
        String[] everything = Permissions.ALL.toArray(String[]::new);
        AuthPrincipal almostOwner = actor("EVERYTHING", everything);

        assertThatThrownBy(() -> AccessPolicy.requireCanGrant(almostOwner, named("OWNER", everything), HELD_BRANCH))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void manage_allowsATargetWithinTheActorsBranchesAndPermissions() {
        HeldAccess cashier = held(HELD_BRANCH, "CASHIER", Permissions.SALES_READ);
        HeldAccess noGrants = new HeldAccess(Map.of(), Set.of());

        assertThatCode(() -> AccessPolicy.requireCanManage(manager(), cashier)).doesNotThrowAnyException();
        assertThatCode(() -> AccessPolicy.requireCanManage(manager(), noGrants)).doesNotThrowAnyException();
    }

    @Test
    void manage_allowsAPeerWithEqualAccess() {
        AuthPrincipal manager = manager();
        HeldAccess peer = new HeldAccess(manager.permissionsByBranch(), manager.roles());

        assertThatCode(() -> AccessPolicy.requireCanManage(manager, peer)).doesNotThrowAnyException();
    }

    @Test
    void manage_refusesATargetHoldingMoreThanTheActor() {
        HeldAccess target = held(HELD_BRANCH, "SOME_ROLE", Permissions.SALES_READ, Permissions.ROLE_WRITE);

        assertThatThrownBy(() -> AccessPolicy.requireCanManage(manager(), target))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void manage_refusesATargetAtABranchTheActorDoesNotHold() {
        HeldAccess elsewhere = held(OTHER_BRANCH, "CASHIER", Permissions.SALES_READ);

        assertThatThrownBy(() -> AccessPolicy.requireCanManage(manager(), elsewhere))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void manage_refusesAnOwnerToANonOwnerHoldingEveryPermission() {
        AuthPrincipal almostOwner = actor("EVERYTHING", Permissions.ALL.toArray(String[]::new));
        HeldAccess owner = new HeldAccess(Map.of(HELD_BRANCH, Set.copyOf(Permissions.ALL)), Set.of("OWNER"));

        assertThatThrownBy(() -> AccessPolicy.requireCanManage(almostOwner, owner))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void grant_isJudgedByTheActorsRoleAtTheTargetBranch() {
        AuthPrincipal actor = managerHereCashierThere();

        assertThatCode(() -> AccessPolicy.requireCanGrant(actor, role(Permissions.SALES_REFUND), HELD_BRANCH))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireCanGrant(actor, role(Permissions.SALES_REFUND), OTHER_BRANCH))
                .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> AccessPolicy.requireCanGrant(actor, role(Permissions.SALES_WRITE), OTHER_BRANCH))
                .doesNotThrowAnyException();
    }

    @Test
    void manage_isJudgedAtEveryBranchTheTargetHolds() {
        AuthPrincipal actor = managerHereCashierThere();
        HeldAccess peerThere = held(OTHER_BRANCH, "CASHIER", Permissions.SALES_WRITE);
        HeldAccess managerThere = new HeldAccess(Map.of(HELD_BRANCH, Set.of(Permissions.SALES_WRITE),
                OTHER_BRANCH, Set.of(Permissions.SALES_WRITE, Permissions.SALES_REFUND)), Set.of("CASHIER", "MANAGER"));

        assertThatCode(() -> AccessPolicy.requireCanManage(actor, peerThere)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireCanManage(actor, managerThere))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void wideningARole_isJudgedAtEveryBranchWhereItIsGranted() {
        AuthPrincipal actor = managerHereCashierThere();
        Set<String> refund = Set.of(Permissions.SALES_REFUND);

        assertThatCode(() -> AccessPolicy.requireCanWidenRole(actor, refund, Set.of(HELD_BRANCH)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AccessPolicy.requireCanWidenRole(actor, refund, Set.of(HELD_BRANCH, OTHER_BRANCH)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> AccessPolicy.requireCanWidenRole(actor, refund, Set.of(UUID.randomUUID())))
                .isInstanceOf(ForbiddenException.class);
        assertThatCode(() -> AccessPolicy.requireCanWidenRole(actor, Set.of(), Set.of(OTHER_BRANCH)))
                .doesNotThrowAnyException();
        assertThatCode(() -> AccessPolicy.requireCanWidenRole(owner(), refund, Set.of(UUID.randomUUID())))
                .doesNotThrowAnyException();
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

        HeldAccess otherOwner = new HeldAccess(Map.of(OTHER_BRANCH, everything), Set.of("OWNER"));

        assertThatCode(() -> {
            AccessPolicy.requireCanGrant(owner(), named("OWNER", Permissions.ALL.toArray(String[]::new)), OTHER_BRANCH);
            AccessPolicy.requireCanManage(owner(), otherOwner);
            AccessPolicy.requireCanEditRole(owner(), everything);
        }).doesNotThrowAnyException();
    }
}
