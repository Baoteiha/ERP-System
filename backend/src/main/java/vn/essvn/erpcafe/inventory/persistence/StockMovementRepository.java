package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);

    Page<StockMovement> findByBranchIdAndIngredientIdOrderByCreatedAtDesc(UUID branchId, UUID ingredientId, Pageable pageable);

    List<StockMovement> findByRefTypeAndRefId(String refType, UUID refId);

    boolean existsByRefTypeAndRefId(String refType, UUID refId);
}
