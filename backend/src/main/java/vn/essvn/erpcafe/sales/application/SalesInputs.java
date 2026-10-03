package vn.essvn.erpcafe.sales.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import vn.essvn.erpcafe.inventory.api.StockShortfall;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.domain.PaymentMethod;

/** Service-layer inputs for the sales use cases. */
public final class SalesInputs {

    public record LineInput(UUID productId, int quantity, Set<UUID> modifierIds, BigDecimal lineDiscount) {
    }

    public record CreateOrderInput(OrderType orderType, BigDecimal orderDiscount, List<LineInput> lines,
            UUID clientRequestId) {
    }

    /** The result of a create: the order, and whether this call made it (false when a retry found it). */
    public record PlacedOrder(Order order, boolean created) {
    }

    /** A priced, unsaved ticket and what the branch is short of to complete it now (empty when nothing). */
    public record QuoteResult(Order order, List<StockShortfall> shortfalls) {
    }

    public record PaymentInput(PaymentMethod method, BigDecimal amount) {
    }

    private SalesInputs() {
    }
}
