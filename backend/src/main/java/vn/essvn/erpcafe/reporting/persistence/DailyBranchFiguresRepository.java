package vn.essvn.erpcafe.reporting.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface DailyBranchFiguresRepository extends JpaRepository<DailyBranchFigures, DailyBranchFigures.Key> {

    /** Each branch's figures over the report days {@code [from, to)}; a branch with no rows is absent. */
    @Query("""
            select new vn.essvn.erpcafe.reporting.persistence.BranchTotals(
                f.branchId, sum(f.orderCount), sum(f.grossSales), sum(f.discountTotal), sum(f.netSales),
                sum(f.taxTotal), sum(f.cogs), sum(f.laborCost))
            from DailyBranchFigures f
            where f.branchId in :branchIds and f.day >= :from and f.day < :to
            group by f.branchId
            """)
    List<BranchTotals> totalsByBranch(@Param("branchIds") Collection<UUID> branchIds,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The branch's days in {@code [from, to)} that saw a sale or a reversal, oldest first. */
    @Query("""
            select f from DailyBranchFigures f
            where f.branchId = :branchId and f.day >= :from and f.day < :to
              and (f.orderCount <> 0 or f.grossSales <> 0 or f.discountTotal <> 0 or f.netSales <> 0
                   or f.taxTotal <> 0)
            order by f.day
            """)
    List<DailyBranchFigures> salesDays(@Param("branchId") UUID branchId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Adds amounts to a branch's day, creating the row the first time. One statement, so two
     * deliveries for the same day cannot lose either's amounts. Native SQL, which the tenant seam
     * does not see: the company is named, and row-level security refuses any other.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            INSERT INTO report_daily_branch (company_id, branch_id, day, order_count, gross_sales,
                                             discount_total, net_sales, tax_total, cogs, labor_cost)
            VALUES (:companyId, :branchId, :day, :orders, :gross, :discount, :net, :tax, :cogs, :labor)
            ON CONFLICT (branch_id, day) DO UPDATE SET
                order_count = report_daily_branch.order_count + EXCLUDED.order_count,
                gross_sales = report_daily_branch.gross_sales + EXCLUDED.gross_sales,
                discount_total = report_daily_branch.discount_total + EXCLUDED.discount_total,
                net_sales = report_daily_branch.net_sales + EXCLUDED.net_sales,
                tax_total = report_daily_branch.tax_total + EXCLUDED.tax_total,
                cogs = report_daily_branch.cogs + EXCLUDED.cogs,
                labor_cost = report_daily_branch.labor_cost + EXCLUDED.labor_cost
            """, nativeQuery = true)
    int add(@Param("companyId") UUID companyId, @Param("branchId") UUID branchId, @Param("day") LocalDate day,
            @Param("orders") long orders, @Param("gross") BigDecimal gross, @Param("discount") BigDecimal discount,
            @Param("net") BigDecimal net, @Param("tax") BigDecimal tax, @Param("cogs") BigDecimal cogs,
            @Param("labor") BigDecimal labor);
}
