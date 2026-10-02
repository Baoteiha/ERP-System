package vn.essvn.erpcafe.reporting.persistence;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Class: DailyBranchFigures
 * Purpose: One branch's sales, cost and labour figures for one report day, as the projection has added them up.
 */
@Entity
@Immutable
@Table(name = "report_daily_branch")
@IdClass(DailyBranchFigures.Key.class)
public class DailyBranchFigures {

    public record Key(UUID branchId, LocalDate day) implements Serializable {
    }

    @Id
    @Column(name = "branch_id")
    private UUID branchId;

    @Id
    @Column(name = "day")
    private LocalDate day;

    // Written only by the projection's upserts inside the company's own transaction; read through the seam.
    @TenantId
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "order_count", nullable = false)
    private long orderCount;

    @Column(name = "gross_sales", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossSales;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal;

    @Column(name = "net_sales", nullable = false, precision = 19, scale = 4)
    private BigDecimal netSales;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "cogs", nullable = false, precision = 19, scale = 4)
    private BigDecimal cogs;

    @Column(name = "labor_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal laborCost;

    protected DailyBranchFigures() {
    }

    public UUID getBranchId() {
        return branchId;
    }

    public LocalDate getDay() {
        return day;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public long getOrderCount() {
        return orderCount;
    }

    public BigDecimal getGrossSales() {
        return grossSales;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getNetSales() {
        return netSales;
    }

    public BigDecimal getTaxTotal() {
        return taxTotal;
    }

    public BigDecimal getCogs() {
        return cogs;
    }

    public BigDecimal getLaborCost() {
        return laborCost;
    }
}
