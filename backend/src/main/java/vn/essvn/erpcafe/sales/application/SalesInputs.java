package vn.essvn.erpcafe.sales.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.domain.PaymentMethod;

/** Service-layer inputs for the sales use cases. */
public final class SalesInputs {

    public record LineInput(UUID productId, int quantity, Set<UUID> modifierIds, BigDecimal lineDiscount) {
    }

    public record CreateOrderInput(OrderType orderType, BigDecimal taxRate, BigDecimal orderDiscount,
            List<LineInput> lines) {
    }

    public record PaymentInput(PaymentMethod method, BigDecimal amount) {
    }

    private SalesInputs() {
    }
}
