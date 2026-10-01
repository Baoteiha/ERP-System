package vn.essvn.erpcafe.staff.web;

import vn.essvn.erpcafe.identity.security.CurrentUser;

/**
 * Class: StaffAccess
 * Purpose: Decides whether the caller may see pay data, which only holders of staff:write may.
 */
final class StaffAccess {

    private StaffAccess() {
    }

    static boolean canSeePay(CurrentUser currentUser) {
        return currentUser.require().permissions().contains("staff:write");
    }
}
