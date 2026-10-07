package vn.essvn.erpcafe.inventory.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;

/**
 * Class: GoodsReceiptRepository
 * Purpose: Reads the goods receipts of purchase orders, oldest first, within the branches row-level security shows.
 */
@Transactional(readOnly = true)
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

    List<GoodsReceipt> findByPurchaseOrderIdOrderByCreatedAtAsc(UUID purchaseOrderId);

    List<GoodsReceipt> findByPurchaseOrderIdInOrderByCreatedAtAsc(Collection<UUID> purchaseOrderIds);

    /** Branch-scoped lookup: empty (→ 404) for a receipt outside the active branch. */
    Optional<GoodsReceipt> findByIdAndBranchId(UUID id, UUID branchId);
}
