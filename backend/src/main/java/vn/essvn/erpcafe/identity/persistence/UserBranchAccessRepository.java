package vn.essvn.erpcafe.identity.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.identity.domain.UserBranchAccess;

public interface UserBranchAccessRepository extends JpaRepository<UserBranchAccess, UUID> {

    List<UserBranchAccess> findByUserId(UUID userId);

    Optional<UserBranchAccess> findByUserIdAndBranchId(UUID userId, UUID branchId);
}
