package vn.essvn.erpcafe.identity.domain;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;

/**
 * Class: AccessPolicy
 * Purpose: Keeps grants, user management and role edits within the actor's own access; equal peers may manage each other.
 */
public final class AccessPolicy {

    // every rule weighs the actor's full grants, never the branch the request acts in: grants, account
    // changes and widening a role in use are judged at the branches affected, the rest against the union (ADR-0007)

    private AccessPolicy() {
    }

    /**
     * The role may be granted only at a branch the actor holds, and carry only permissions the actor's
     * own role has at that branch. Only an owner grants or revokes OWNER, even to an actor whose
     * permissions cover all of OWNER's.
     */
    public static void requireCanGrant(AuthPrincipal actor, Role role, UUID branchId) {
        if (isOwner(actor)) {
            return;
        }
        if (DefaultRole.OWNER.roleName().equals(role.getName())) {
            throw new ForbiddenException("Only an owner can grant or revoke the OWNER role");
        }
        if (!actor.canAccessBranch(branchId)) {
            throw new ForbiddenException("You can only grant access at branches you hold");
        }
        Set<String> rolePermissions = role.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
        if (!actor.permissionsAt(branchId).containsAll(rolePermissions)) {
            throw new ForbiddenException("You can only grant roles whose permissions you hold at that branch");
        }
    }

    /**
     * Editing, resetting, disabling or re-granting a user needs every branch that user holds and, at
     * each, every permission they have there, so nobody can take over an account to act where they
     * could not. Only an owner manages an owner.
     */
    public static void requireCanManage(AuthPrincipal actor, HeldAccess target) {
        if (isOwner(actor)) {
            return;
        }
        if (target.roleNames().contains(DefaultRole.OWNER.roleName())) {
            throw new ForbiddenException("Only an owner can manage an owner");
        }
        if (!actor.branchIds().containsAll(target.branchIds())) {
            throw new ForbiddenException("You can only manage users at branches you hold");
        }
        target.permissionsByBranch().forEach((branchId, permissions) -> {
            if (!actor.permissionsAt(branchId).containsAll(permissions)) {
                throw new ForbiddenException(
                        "You can only manage users whose permissions you hold at each of their branches");
            }
        });
    }

    /**
     * Permissions added to a role reach everyone who holds it, wherever they hold it: at every branch
     * where the role is granted, the actor must hold the branch and the added permissions there.
     */
    public static void requireCanWidenRole(AuthPrincipal actor, Set<String> added, Set<UUID> grantedAt) {
        if (isOwner(actor) || added.isEmpty()) {
            return;
        }
        for (UUID branchId : grantedAt) {
            // a branch the actor does not hold has no permissions for them, so this refuses it too
            if (!actor.permissionsAt(branchId).containsAll(added)) {
                throw new ForbiddenException(
                        "You can only widen a role with permissions you hold at every branch where it is granted");
            }
        }
    }

    /** A role may be given only permissions the actor holds; a role has no branch, so at any of theirs. */
    public static void requireCanEditRole(AuthPrincipal actor, Set<String> newPermissions) {
        if (!isOwner(actor) && !actor.allPermissions().containsAll(newPermissions)) {
            throw new ForbiddenException("You can only give a role permissions you hold");
        }
    }

    private static boolean isOwner(AuthPrincipal actor) {
        return actor.roles().contains(DefaultRole.OWNER.roleName());
    }
}
