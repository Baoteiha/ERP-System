package vn.essvn.erpcafe.reporting.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Class: ShiftLabor
 * Purpose: The labour one completed shift currently adds to a report day, so a restated shift can take it back first.
 */
@Entity
@Table(name = "report_shift_labor")
public class ShiftLabor {

    @Id
    @Column(name = "shift_id")
    private UUID shiftId;

    // Hibernate refuses the insert unless this is the session's company.
    @TenantId
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "labor_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal laborCost;

    protected ShiftLabor() {
    }

    public ShiftLabor(UUID shiftId, UUID companyId, UUID branchId, LocalDate day, BigDecimal laborCost) {
        this.shiftId = shiftId;
        this.companyId = companyId;
        this.branchId = branchId;
        this.day = day;
        this.laborCost = laborCost;
    }

    public void restate(LocalDate day, BigDecimal laborCost) {
        this.day = day;
        this.laborCost = laborCost;
    }

    public UUID getShiftId() {
        return shiftId;
    }

    public UUID getBranchId() {
        return branchId;
    }

    public LocalDate getDay() {
        return day;
    }

    public BigDecimal getLaborCost() {
        return laborCost;
    }
}
