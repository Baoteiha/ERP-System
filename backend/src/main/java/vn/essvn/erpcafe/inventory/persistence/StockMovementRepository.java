package vn.essvn.erpcafe.inventory.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.inventory.domain.MovementType;
import vn.essvn.erpcafe.inventory.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    /** Σ |quantity| × unit cost of one movement type at a branch in [from, to). */
    @Query("""
            select coalesce(sum(abs(m.quantity) * m.unitCost.amount), 0)
            from StockMovement m
            where m.branchId = :branchId and m.type = :type
              and m.createdAt >= :from and m.createdAt < :to
            """)
    BigDecimal costOfType(@Param("branchId") UUID branchId, @Param("type") MovementType type,
            @Param("from") Instant from, @Param("to") Instant to);

    Page<StockMovement> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);

    Page<StockMovement> findByBranchIdAndIngredientIdOrderByCreatedAtDesc(UUID branchId, UUID ingredientId, Pageable pageable);

    List<StockMovement> findByRefTypeAndRefId(String refType, UUID refId);

    boolean existsByRefTypeAndRefId(String refType, UUID refId);
}
