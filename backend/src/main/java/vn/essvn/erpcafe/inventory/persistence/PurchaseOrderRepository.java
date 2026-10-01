package vn.essvn.erpcafe.inventory.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderStatus;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Page<PurchaseOrder> findByBranchId(UUID branchId, Pageable pageable);

    /** Branch-scoped lookup: returns empty (→ 404) for a PO outside the active branch. */
    Optional<PurchaseOrder> findByIdAndBranchId(UUID id, UUID branchId);

    /** True when a not-cancelled order still expects some of the ingredient. */
    @Query("""
            SELECT COUNT(po) > 0 FROM PurchaseOrder po JOIN po.lines l
            WHERE l.ingredientId = :ingredientId AND po.status <> :cancelled AND l.receivedQty < l.orderedQty
            """)
    boolean existsOpenLine(@Param("ingredientId") UUID ingredientId,
            @Param("cancelled") PurchaseOrderStatus cancelled);
}
