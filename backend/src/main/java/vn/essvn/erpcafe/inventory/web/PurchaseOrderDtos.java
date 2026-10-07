package vn.essvn.erpcafe.inventory.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.inventory.domain.OverDeliveryKind;

public final class PurchaseOrderDtos {

    public record PoLineRequest(
            UUID itemId,
            UUID ingredientId,
            @Size(max = 16) String unit,
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
            // What was delivered: whole packs on a pack line, otherwise in `unit` (blank = the line's unit).
            @NotNull @Positive BigDecimal receivedQty,
            @Size(max = 16) String unit,
            // The invoice price when it differs from the order: per pack, or per `unit`.
            @PositiveOrZero BigDecimal unitCostOverride,
            @PositiveOrZero BigDecimal damagedQty,
            OverDeliveryKind overDeliveryKind,
            @Size(max = 255) String note) {
    }

    public record ReceiveRequest(
            @Size(max = 255) String note,
            @NotEmpty @Valid List<ReceiptRequest> receipts) {
    }

    /**
     * Class: CompensationLineRequest
     * Purpose: One lost receipt line and how many replacement units the supplier sent for it.
     */
    public record CompensationLineRequest(
            @NotNull UUID receiptLineId,
            @NotNull @Positive BigDecimal quantity) {
    }

    /**
     * Class: CompensationRequest
     * Purpose: A supplier's replacement for lost units, as lines against earlier receipt lines.
     */
    public record CompensationRequest(
            @Size(max = 255) String note,
            @NotEmpty @Valid List<CompensationLineRequest> lines) {
    }

    /**
     * Class: GoodsReceiptLineResponse
     * Purpose: What one purchase line received, as the API returns it.
     */
    public record GoodsReceiptLineResponse(
            UUID id,
            UUID purchaseOrderLineId,
            BigDecimal deliveredQty,
            BigDecimal damagedQty,
            BigDecimal lossQty,
            BigDecimal overDeliveredQty,
            String overDeliveryKind,
            MoneyDto unitCost,
            BigDecimal costPerUnit,
            BigDecimal compensatedQty,
            BigDecimal replacementQty,
            UUID compensatesLineId,
            MoneyDto lossAmount,
            String note) {
    }

    /**
     * Class: GoodsReceiptResponse
     * Purpose: One goods receipt with its lines, as the API returns it.
     */
    public record GoodsReceiptResponse(
            UUID id,
            String kind,
            String note,
            Instant receivedAt,
            String receivedBy,
            List<GoodsReceiptLineResponse> lines) {
    }

    public record PoLineResponse(
            UUID id,
            UUID itemId,
            UUID ingredientId,
            String unit,
            String packLabel,
            BigDecimal packSize,
            BigDecimal orderedQty,
            BigDecimal receivedQty,
            MoneyDto unitCost) {
    }

    public record PurchaseOrderResponse(
            UUID id,
            UUID branchId,
            UUID supplierId,
            String kind,
            String status,
            String note,
            List<PoLineResponse> lines,
            List<GoodsReceiptResponse> receipts) {
    }

    private PurchaseOrderDtos() {
    }

    /**
     * Class: RetailLineRequest
     * Purpose: One item bought at a shop, with the quantity carried in and the unit cost paid.
     */
    public record RetailLineRequest(
            @NotNull UUID itemId,
            @NotNull @Positive BigDecimal quantity,
            @NotNull @PositiveOrZero BigDecimal unitCost) {
    }

    /**
     * Class: RetailPurchaseRequest
     * Purpose: The body of a retail purchase: the shop it came from, an optional note and the lines bought.
     */
    public record RetailPurchaseRequest(
            @NotNull UUID supplierId,
            @Size(max = 255) String note,
            @NotEmpty @Valid List<RetailLineRequest> lines) {
    }
}
