package vn.essvn.erpcafe.identity.api;

import java.util.UUID;

/**
 * Published facade of the identity module. Other modules ask it who the caller is and what the caller
 * may do, without depending on identity's security internals. Every method needs an authenticated
 * caller and answers for the request in progress.
 */
public interface IdentityApi {

    /** The authenticated user's id; an unauthenticated caller is refused with a 401. */
    UUID requireUserId();

    /** Whether the caller holds the permission where this request acts (the active branch, or any branch when none is named). */
    boolean hasPermission(String permission);

    /** Whether the caller's role at that branch carries the permission, whichever branch the request acts in. */
    boolean hasPermissionAt(UUID branchId, String permission);

    /** Whether the caller holds the OWNER role at any branch. */
    boolean isOwner();
}
