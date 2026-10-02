package vn.essvn.erpcafe.sales.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderStatus;

@Transactional(readOnly = true)
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Branch-scoped lookup: empty (→ 404) for an order at any other branch. */
    Optional<Order> findByIdAndBranchId(UUID id, UUID branchId);

    boolean existsByBranchIdAndStatus(UUID branchId, OrderStatus status);

    Optional<Order> findByBranchIdAndClientRequestId(UUID branchId, UUID clientRequestId);

    Page<Order> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);
}
