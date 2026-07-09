package vn.essvn.erpcafe.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Published facade of the identity module. Other modules use this to learn who
 * the current user is (e.g. sales recording the cashier) without depending on
 * identity's internals.
 */
public interface IdentityApi {

    /** The authenticated user's id, if a user is authenticated. */
    Optional<UUID> currentUserId();

    /** The authenticated user's company id, if authenticated. */
    Optional<UUID> currentCompanyId();
}
