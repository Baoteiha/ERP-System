package vn.essvn.erpcafe.sales.persistence;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.sales.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);
}
