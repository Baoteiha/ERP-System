package vn.essvn.erpcafe.sales.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.sales.api.SalesSummary;
import vn.essvn.erpcafe.sales.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Branch-scoped lookup: empty (→ 404) for an order at any other branch. */
    Optional<Order> findByIdAndBranchId(UUID id, UUID branchId);

    Page<Order> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);

    /** Sales totals over [from, to) for PAID/COMPLETED orders at a branch. */
    @Query("""
            select new vn.essvn.erpcafe.sales.api.SalesSummary(
                count(o),
                coalesce(sum(o.subtotal), 0),
                coalesce(sum(o.discountTotal), 0),
                coalesce(sum(o.subtotal - o.discountTotal), 0),
                coalesce(sum(o.taxTotal), 0),
                coalesce(sum(o.grandTotal), 0))
            from Order o
            where o.branchId = :branchId
              and o.status in (vn.essvn.erpcafe.sales.domain.OrderStatus.PAID,
                               vn.essvn.erpcafe.sales.domain.OrderStatus.COMPLETED)
              and o.createdAt >= :from and o.createdAt < :to
            """)
    SalesSummary summarize(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);

    /** Rows of (day, order count, net sales) per calendar day in {@code zone} over [from, to). */
    @Query(value = """
            select cast(o.created_at at time zone :zone as date) as day,
                   count(*) as orders,
                   coalesce(sum(o.subtotal - o.discount_total), 0) as net_sales
            from sales_order o
            where o.branch_id = :branchId
              and o.status in ('PAID', 'COMPLETED')
              and o.created_at >= :from and o.created_at < :to
            group by day
            order by day
            """, nativeQuery = true)
    List<Object[]> netSalesByDay(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("zone") String zone);
}
