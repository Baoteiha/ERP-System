package vn.essvn.erpcafe.identity.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Class: HeldAccess
 * Purpose: The branches, role names and permissions a user holds across their grants, as the access policy weighs them.
 */
public record HeldAccess(Map<UUID, Set<String>> permissionsByBranch, Set<String> roleNames) {

    public Set<UUID> branchIds() {
        return permissionsByBranch.keySet();
    }
}
