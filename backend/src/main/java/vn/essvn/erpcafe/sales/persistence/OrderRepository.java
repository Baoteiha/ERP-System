package vn.essvn.erpcafe.sales.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.sales.api.SalesSummary;
import vn.essvn.erpcafe.sales.domain.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);

    /** Revenue totals over [from, to) for PAID/COMPLETED orders at a branch. */
    @Query("""
            select new vn.essvn.erpcafe.sales.api.SalesSummary(
                count(o),
                coalesce(sum(o.subtotal), 0),
                coalesce(sum(o.discountTotal), 0),
                coalesce(sum(o.taxTotal), 0),
                coalesce(sum(o.grandTotal), 0),
                coalesce(sum(coalesce(o.cogsTotal, 0)), 0))
            from Order o
            where o.branchId = :branchId
              and o.status in (vn.essvn.erpcafe.sales.domain.OrderStatus.PAID,
                               vn.essvn.erpcafe.sales.domain.OrderStatus.COMPLETED)
              and o.createdAt >= :from and o.createdAt < :to
            """)
    SalesSummary summarize(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);

    /** Rows of (day, order count, revenue) per UTC calendar day over [from, to). */
    @Query(value = """
            select cast(o.created_at at time zone 'UTC' as date) as day,
                   count(*) as orders,
                   coalesce(sum(o.grand_total), 0) as revenue
            from sales_order o
            where o.branch_id = :branchId
              and o.status in ('PAID', 'COMPLETED')
              and o.created_at >= :from and o.created_at < :to
            group by day
            order by day
            """, nativeQuery = true)
    List<Object[]> revenueByDay(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);
}
