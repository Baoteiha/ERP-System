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

    /**
     * Sales booked in [from, to): orders by completion time, or by creation time when they never completed
     * (legacy PAID rows). VOID/REFUNDED orders were sales too; {@link #reversalsOf} takes them back.
     */
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
                               vn.essvn.erpcafe.sales.domain.OrderStatus.COMPLETED,
                               vn.essvn.erpcafe.sales.domain.OrderStatus.VOID,
                               vn.essvn.erpcafe.sales.domain.OrderStatus.REFUNDED)
              and ((o.completedAt >= :from and o.completedAt < :to)
                or (o.completedAt is null and o.createdAt >= :from and o.createdAt < :to))
            """)
    SalesSummary salesBooked(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);

    /** Totals of the sales voided or refunded in [from, to), by the time of the reversal. */
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
              and o.status in (vn.essvn.erpcafe.sales.domain.OrderStatus.VOID,
                               vn.essvn.erpcafe.sales.domain.OrderStatus.REFUNDED)
              and o.reversedAt >= :from and o.reversedAt < :to
            """)
    SalesSummary reversalsOf(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Rows of (day, order count, net sales) per calendar day in {@code zone} over [from, to): each sale is
     * booked on the day it completed (created, for legacy PAID rows) and each reversal as a negative on its own day.
     */
    @Query(value = """
            select day, sum(orders) as orders, sum(net_sales) as net_sales
            from (
                select cast(coalesce(o.completed_at, o.created_at) at time zone :zone as date) as day,
                       1 as orders,
                       o.subtotal - o.discount_total as net_sales
                from sales_order o
                where o.branch_id = :branchId
                  and o.status in ('PAID', 'COMPLETED', 'VOID', 'REFUNDED')
                  and ((o.completed_at >= :from and o.completed_at < :to)
                    or (o.completed_at is null and o.created_at >= :from and o.created_at < :to))
                union all
                select cast(o.reversed_at at time zone :zone as date) as day,
                       0 as orders,
                       o.discount_total - o.subtotal as net_sales
                from sales_order o
                where o.branch_id = :branchId
                  and o.status in ('VOID', 'REFUNDED')
                  and o.reversed_at >= :from and o.reversed_at < :to
            ) booked
            group by day
            order by day
            """, nativeQuery = true)
    List<Object[]> netSalesByDay(@Param("branchId") UUID branchId,
            @Param("from") Instant from, @Param("to") Instant to,
            @Param("zone") String zone);
}
