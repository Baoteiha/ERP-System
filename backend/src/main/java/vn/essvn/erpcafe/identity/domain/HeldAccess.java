package vn.essvn.erpcafe.identity.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Class: HeldAccess
 * Purpose: The branches, role names and permissions a user holds across their grants, as the access policy weighs them.
 */
public record HeldAccess(Set<UUID> branchIds, Set<String> roleNames, Set<String> permissions) {
}
