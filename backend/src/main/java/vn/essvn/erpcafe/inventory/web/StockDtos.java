package vn.essvn.erpcafe.inventory.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import vn.essvn.erpcafe.common.web.MoneyDto;

public final class StockDtos {

    public record StockItemResponse(
            UUID ingredientId,
            UUID branchId,
            BigDecimal quantityOnHand,
            BigDecimal reorderLevel,
            MoneyDto avgUnitCost,
            boolean belowReorderLevel) {
    }

    public record MovementResponse(
            UUID id,
            UUID ingredientId,
            UUID branchId,
            String type,
            BigDecimal quantity,
            MoneyDto unitCost,
            String refType,
            UUID refId,
            String note,
            Instant occurredAt,
            String actor) {
    }

    public record AdjustRequest(
            @NotNull UUID ingredientId,
            @NotNull BigDecimal quantityDelta,
            String note) {
    }

    public record WasteRequest(
            @NotNull UUID ingredientId,
            @NotNull @Positive BigDecimal quantity,
            String note) {
    }

    public record ReorderLevelRequest(
            @NotNull UUID ingredientId,
            @NotNull @PositiveOrZero BigDecimal reorderLevel) {
    }

    private StockDtos() {
    }
}
