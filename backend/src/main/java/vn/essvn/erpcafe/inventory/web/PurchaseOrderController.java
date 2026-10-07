package vn.essvn.erpcafe.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.LineInput;
import vn.essvn.erpcafe.inventory.application.PurchaseInputs.ReceiptInput;
import vn.essvn.erpcafe.inventory.application.PurchaseOrderService;
import vn.essvn.erpcafe.inventory.domain.GoodsReceipt;
import vn.essvn.erpcafe.inventory.domain.GoodsReceiptLine;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.CreatePurchaseOrderRequest;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.GoodsReceiptLineResponse;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.GoodsReceiptResponse;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.PoLineResponse;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.PurchaseOrderResponse;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.ReceiveRequest;

/**
 * Purchase orders for the active branch (requires {@code X-Branch-Id}):
 * create → send → receive / cancel.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    public PurchaseOrderController(PurchaseOrderService purchaseOrderService) {
        this.purchaseOrderService = purchaseOrderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PageResponse<PurchaseOrderResponse> list(
            @PageableDefault(sort = "createdAt", direction = Direction.DESC) Pageable pageable) {
        Page<PurchaseOrder> page = purchaseOrderService.list(pageable);
        Map<UUID, List<GoodsReceipt>> receipts = purchaseOrderService.receiptsOf(
                page.getContent().stream().map(PurchaseOrder::getId).toList());
        return PageResponse.from(page.map(o -> toResponse(o, receipts.getOrDefault(o.getId(), List.of()))));
    }

    @GetMapping("/by-receipt/{receiptId}")
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PurchaseOrderResponse byReceipt(@PathVariable UUID receiptId) {
        return respond(purchaseOrderService.getByReceipt(receiptId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return respond(purchaseOrderService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchasing:write')")
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(l.itemId(), l.ingredientId(), l.unit(), l.orderedQty(), l.unitCost()))
                .toList();
        PurchaseOrderResponse body = respond(
                purchaseOrderService.create(request.supplierId(), request.note(), lines));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/purchase-orders/" + body.id())).body(body);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse send(@PathVariable UUID id) {
        return respond(purchaseOrderService.send(id));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse receive(@PathVariable UUID id, @Valid @RequestBody ReceiveRequest request) {
        List<ReceiptInput> receipts = request.receipts().stream()
                .map(r -> new ReceiptInput(r.lineId(), r.receivedQty(), r.unit(), r.unitCostOverride(),
                        r.damagedQty(), r.overDeliveryKind(), r.note()))
                .toList();
        return respond(purchaseOrderService.receive(id, receipts, request.note()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse cancel(@PathVariable UUID id) {
        return respond(purchaseOrderService.cancel(id));
    }

    private PurchaseOrderResponse respond(PurchaseOrder o) {
        return toResponse(o, purchaseOrderService.receiptsOf(List.of(o.getId())).getOrDefault(o.getId(), List.of()));
    }

    private static PurchaseOrderResponse toResponse(PurchaseOrder o, List<GoodsReceipt> receipts) {
        List<PoLineResponse> lines = o.getLines().stream().map(PurchaseOrderController::toLine).toList();
        return new PurchaseOrderResponse(o.getId(), o.getBranchId(), o.getSupplierId(), o.getKind().name(),
                o.getStatus().name(), o.getNote(), lines,
                receipts.stream().map(PurchaseOrderController::toReceipt).toList());
    }

    private static GoodsReceiptResponse toReceipt(GoodsReceipt r) {
        return new GoodsReceiptResponse(r.getId(), r.getKind().name(), r.getNote(), r.getCreatedAt(),
                r.getCreatedBy(), r.getLines().stream().map(PurchaseOrderController::toReceiptLine).toList());
    }

    private static GoodsReceiptLineResponse toReceiptLine(GoodsReceiptLine l) {
        return new GoodsReceiptLineResponse(l.getId(), l.getPurchaseOrderLineId(), l.getDeliveredQty(),
                l.getDamagedQty(), l.getLossQty(), l.getOverDeliveredQty(),
                l.getOverDeliveryKind() == null ? null : l.getOverDeliveryKind().name(),
                MoneyDto.from(l.getUnitCost()), l.getCostPerUnit(), l.getCompensatedQty(), l.getReplacementQty(),
                l.getCompensatesLineId(), MoneyDto.from(l.lossAmount()), l.getNote());
    }

    private static PoLineResponse toLine(PurchaseOrderLine l) {
        return new PoLineResponse(l.getId(), l.getItemId(), l.getIngredientId(), l.getUnit(), l.getPackLabel(),
                l.getPackSize(), l.getOrderedQty(), l.getReceivedQty(), MoneyDto.from(l.getUnitCost()));
    }
}
