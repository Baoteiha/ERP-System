package vn.essvn.erpcafe.sales.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.sales.domain.OrderType;
import vn.essvn.erpcafe.sales.domain.PaymentMethod;

public final class OrderDtos {

    // --- requests ---

    public record LineRequest(
            @NotNull UUID productId,
            @Positive int quantity,
            Set<UUID> modifierIds,
            @PositiveOrZero BigDecimal lineDiscount) {
    }

    public record CreateOrderRequest(
            @NotNull OrderType orderType,
            @PositiveOrZero BigDecimal taxRate,
            @PositiveOrZero BigDecimal orderDiscount,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    public record PaymentRequest(
            @NotNull PaymentMethod method,
            @NotNull @Positive BigDecimal amount) {
    }

    public record RefundRequest(
            PaymentMethod method) {
    }

    // --- responses ---

    public record ModifierResponse(UUID modifierId, String name, MoneyDto priceDelta) {
    }

    public record LineResponse(
            UUID id,
            UUID productId,
            String productName,
            MoneyDto unitPrice,
            int quantity,
            MoneyDto lineDiscount,
            MoneyDto lineTotal,
            List<ModifierResponse> modifiers) {
    }

    public record PaymentResponse(String method, String type, MoneyDto amount, Instant at) {
    }

    public record OrderResponse(
            UUID id,
            UUID branchId,
            UUID cashierUserId,
            String orderType,
            String status,
            String currency,
            BigDecimal taxRate,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal taxTotal,
            BigDecimal grandTotal,
            BigDecimal cogsTotal,
            BigDecimal amountPaid,
            List<LineResponse> lines,
            List<PaymentResponse> payments,
            Instant createdAt) {
    }

    private OrderDtos() {
    }
}
