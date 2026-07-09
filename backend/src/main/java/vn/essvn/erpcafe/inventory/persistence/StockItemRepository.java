package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import vn.essvn.erpcafe.inventory.domain.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    List<StockItem> findByBranchId(UUID branchId);

    Optional<StockItem> findByIngredientIdAndBranchId(UUID ingredientId, UUID branchId);

    /**
     * Pessimistic-locking lookup used on the stock-mutation path so concurrent
     * receipts/depletions of the same item serialize instead of losing updates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockItem s WHERE s.ingredientId = :ingredientId AND s.branchId = :branchId")
    Optional<StockItem> findForUpdate(@Param("ingredientId") UUID ingredientId, @Param("branchId") UUID branchId);

    @Query("SELECT s FROM StockItem s WHERE s.branchId = :branchId AND s.quantityOnHand < s.reorderLevel")
    List<StockItem> findLowStock(@Param("branchId") UUID branchId);
}
