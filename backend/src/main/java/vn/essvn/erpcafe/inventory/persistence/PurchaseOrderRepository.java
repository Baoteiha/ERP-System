package vn.essvn.erpcafe.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Page<PurchaseOrder> findByBranchId(UUID branchId, Pageable pageable);

    /** Branch-scoped lookup: returns empty (→ 404) for a PO outside the active branch. */
    Optional<PurchaseOrder> findByIdAndBranchId(UUID id, UUID branchId);
}
