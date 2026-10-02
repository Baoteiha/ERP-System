package vn.essvn.erpcafe.staff.api;

import java.util.UUID;

/**
 * Published facade of the staff module: what other modules need to ask of it.
 */
public interface StaffApi {

    /** Whether someone is clocked in at the company's branch right now. */
    boolean hasOpenShifts(UUID companyId, UUID branchId);
}
