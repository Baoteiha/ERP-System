package vn.essvn.erpcafe.identity.domain;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;

/**
 * Class: AccessPolicy
 * Purpose: Decides whether an actor may grant a role, manage a user or edit a role, never beyond what they hold.
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /** The role may carry only permissions the actor holds, granted only at a branch the actor holds. */
    public static void requireCanGrant(AuthPrincipal actor, Role role, UUID branchId) {
        if (isOwner(actor)) {
            return;
        }
        if (!actor.canAccessBranch(branchId)) {
            throw new ForbiddenException("You can only grant access at branches you hold");
        }
        Set<String> rolePermissions = role.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
        if (!actor.permissions().containsAll(rolePermissions)) {
            throw new ForbiddenException("You can only grant roles whose permissions you hold");
        }
    }

    /** Editing, resetting, disabling or re-granting a user needs every permission that user holds. */
    public static void requireCanManage(AuthPrincipal actor, Set<String> targetPermissions) {
        if (!isOwner(actor) && !actor.permissions().containsAll(targetPermissions)) {
            throw new ForbiddenException("You can only manage users whose permissions you hold");
        }
    }

    /** A role may be given only permissions the actor holds. */
    public static void requireCanEditRole(AuthPrincipal actor, Set<String> newPermissions) {
        if (!isOwner(actor) && !actor.permissions().containsAll(newPermissions)) {
            throw new ForbiddenException("You can only give a role permissions you hold");
        }
    }

    private static boolean isOwner(AuthPrincipal actor) {
        return actor.roles().contains(DefaultRole.OWNER.roleName());
    }
}
