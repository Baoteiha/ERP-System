package vn.essvn.erpcafe.catalog.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.ProductBranchAvailability;

@Transactional(readOnly = true)
public interface ProductBranchAvailabilityRepository extends JpaRepository<ProductBranchAvailability, UUID> {

    Optional<ProductBranchAvailability> findByProductIdAndBranchId(UUID productId, UUID branchId);

    List<ProductBranchAvailability> findByProductId(UUID productId);

    List<ProductBranchAvailability> findByBranchId(UUID branchId);
}
