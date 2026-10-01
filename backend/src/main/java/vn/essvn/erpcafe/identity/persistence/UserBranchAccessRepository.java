package vn.essvn.erpcafe.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.identity.domain.UserBranchAccess;

@Transactional(readOnly = true)
public interface UserBranchAccessRepository extends JpaRepository<UserBranchAccess, UUID> {

    List<UserBranchAccess> findByUserId(UUID userId);

    Optional<UserBranchAccess> findByUserIdAndBranchId(UUID userId, UUID branchId);

    List<UserBranchAccess> findByBranchId(UUID branchId);

    List<UserBranchAccess> findByRoleId(UUID roleId);

    /** Guards role deletion: a role somebody still holds cannot be removed. */
    boolean existsByRoleId(UUID roleId);
}
