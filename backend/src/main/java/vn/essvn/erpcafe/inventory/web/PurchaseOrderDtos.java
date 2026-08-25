package vn.essvn.erpcafe.inventory.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MoneyDto;

public final class PurchaseOrderDtos {

    public record PoLineRequest(
            @NotNull UUID ingredientId,
            @NotNull @Positive BigDecimal orderedQty,
            @NotNull @PositiveOrZero BigDecimal unitCost) {
    }

    public record CreatePurchaseOrderRequest(
            @NotNull UUID supplierId,
            String note,
            @NotEmpty @Valid List<PoLineRequest> lines) {
    }

    public record ReceiptRequest(
            @NotNull UUID lineId,
            @NotNull @Positive BigDecimal receivedQty,
            @Size(max = 16) String unit,
            BigDecimal unitCostOverride) {
    }

    public record ReceiveRequest(
            @NotEmpty @Valid List<ReceiptRequest> receipts) {
    }

    public record PoLineResponse(
            UUID id,
            UUID ingredientId,
            BigDecimal orderedQty,
            BigDecimal receivedQty,
            MoneyDto unitCost) {
    }

    public record PurchaseOrderResponse(
            UUID id,
            UUID branchId,
            UUID supplierId,
            String status,
            String note,
            List<PoLineResponse> lines) {
    }

    private PurchaseOrderDtos() {
    }
}
