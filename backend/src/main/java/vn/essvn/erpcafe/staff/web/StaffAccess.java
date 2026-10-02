package vn.essvn.erpcafe.staff.web;

import vn.essvn.erpcafe.identity.api.IdentityApi;

/**
 * Class: StaffAccess
 * Purpose: Decides whether the caller may see pay data, which only holders of staff:write may.
 */
final class StaffAccess {

    private StaffAccess() {
    }

    static boolean canSeePay(IdentityApi identityApi) {
        return identityApi.hasPermission("staff:write");
    }
}
