package vn.essvn.erpcafe.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.events.DomainEvent;
import vn.essvn.erpcafe.reporting.persistence.AppliedEventRepository;
import vn.essvn.erpcafe.reporting.persistence.DailyBranchFiguresRepository;
import vn.essvn.erpcafe.reporting.persistence.ShiftLabor;
import vn.essvn.erpcafe.reporting.persistence.ShiftLaborRepository;
import vn.essvn.erpcafe.sales.api.OrderCompleted;
import vn.essvn.erpcafe.sales.api.OrderReversed;
import vn.essvn.erpcafe.staff.api.ShiftCompleted;

/**
 * Class: ReportProjection
 * Purpose: Keeps the daily branch figures in step with the sales and shifts other modules announce, applying each event once.
 */
@Component
public class ReportProjection {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final DailyBranchFiguresRepository figures;
    private final ShiftLaborRepository shiftLabor;
    private final AppliedEventRepository appliedEvents;
    private final ReportingProperties properties;

    public ReportProjection(DailyBranchFiguresRepository figures, ShiftLaborRepository shiftLabor,
            AppliedEventRepository appliedEvents, ReportingProperties properties) {
        this.figures = figures;
        this.shiftLabor = shiftLabor;
        this.appliedEvents = appliedEvents;
        this.properties = properties;
    }

    // Each listener runs in the dispatcher's delivery transaction, inside the event's company: the
    // figures and the event's processed mark commit together. Rows take the company the event was
    // recorded under, never one its payload names.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onOrderCompleted(DomainEvent<OrderCompleted> event) {
        if (!firstDelivery(event)) {
            return;
        }
        OrderCompleted sale = event.payload();
        figures.add(event.companyId(), sale.branchId(), dayOf(sale.occurredAt()), 1, sale.grossSales(),
                sale.discountTotal(), sale.netSales(), sale.taxTotal(), sale.cogs(), ZERO);
        if (sale.previouslyBookedAt() != null) {
            // a legacy PAID order was booked on the day it was created; it moves to its completion day
            figures.add(event.companyId(), sale.branchId(), dayOf(sale.previouslyBookedAt()), -1,
                    sale.grossSales().negate(), sale.discountTotal().negate(), sale.netSales().negate(),
                    sale.taxTotal().negate(), ZERO, ZERO);
        }
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onOrderReversed(DomainEvent<OrderReversed> event) {
        if (!firstDelivery(event)) {
            return;
        }
        // the order count stays the sales made; only the money is taken back
        OrderReversed reversal = event.payload();
        figures.add(event.companyId(), reversal.branchId(), dayOf(reversal.occurredAt()), 0,
                reversal.grossSales().negate(), reversal.discountTotal().negate(), reversal.netSales().negate(),
                reversal.taxTotal().negate(), reversal.cogs().negate(), ZERO);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onShiftCompleted(DomainEvent<ShiftCompleted> event) {
        if (!firstDelivery(event)) {
            return;
        }
        ShiftCompleted shift = event.payload();
        LocalDate day = dayOf(shift.clockOut());
        // an adjustment restates the shift: what it added before comes off first, wherever it was booked
        shiftLabor.lockByShiftId(shift.shiftId()).ifPresentOrElse(
                previous -> {
                    addLabor(event.companyId(), previous.getBranchId(), previous.getDay(),
                            previous.getLaborCost().negate());
                    previous.restate(day, shift.laborCost());
                },
                () -> shiftLabor.save(
                        new ShiftLabor(shift.shiftId(), event.companyId(), shift.branchId(), day, shift.laborCost())));
        addLabor(event.companyId(), shift.branchId(), day, shift.laborCost());
    }

    private boolean firstDelivery(DomainEvent<?> event) {
        return appliedEvents.claim(event.id(), event.companyId()) == 1;
    }

    private void addLabor(UUID companyId, UUID branchId, LocalDate day, BigDecimal labor) {
        figures.add(companyId, branchId, day, 0, ZERO, ZERO, ZERO, ZERO, ZERO, labor);
    }

    private LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(at, properties.zone());
    }
}
