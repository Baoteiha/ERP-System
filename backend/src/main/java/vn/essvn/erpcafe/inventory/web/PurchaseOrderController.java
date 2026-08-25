package vn.essvn.erpcafe.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
import vn.essvn.erpcafe.inventory.domain.PurchaseOrder;
import vn.essvn.erpcafe.inventory.domain.PurchaseOrderLine;
import vn.essvn.erpcafe.inventory.web.PurchaseOrderDtos.CreatePurchaseOrderRequest;
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
    public PageResponse<PurchaseOrderResponse> list(Pageable pageable) {
        return PageResponse.from(purchaseOrderService.list(pageable).map(PurchaseOrderController::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchasing:read')")
    public PurchaseOrderResponse get(@PathVariable UUID id) {
        return toResponse(purchaseOrderService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchasing:write')")
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(l.ingredientId(), l.orderedQty(), l.unitCost()))
                .toList();
        PurchaseOrderResponse body = toResponse(
                purchaseOrderService.create(request.supplierId(), request.note(), lines));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/purchase-orders/" + body.id())).body(body);
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse send(@PathVariable UUID id) {
        return toResponse(purchaseOrderService.send(id));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse receive(@PathVariable UUID id, @Valid @RequestBody ReceiveRequest request) {
        List<ReceiptInput> receipts = request.receipts().stream()
                .map(r -> new ReceiptInput(r.lineId(), r.receivedQty(), r.unit(), r.unitCostOverride()))
                .toList();
        return toResponse(purchaseOrderService.receive(id, receipts));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchasing:write')")
    public PurchaseOrderResponse cancel(@PathVariable UUID id) {
        return toResponse(purchaseOrderService.cancel(id));
    }

    private static PurchaseOrderResponse toResponse(PurchaseOrder o) {
        List<PoLineResponse> lines = o.getLines().stream()
                .map(PurchaseOrderController::toLine)
                .toList();
        return new PurchaseOrderResponse(o.getId(), o.getBranchId(), o.getSupplierId(),
                o.getStatus().name(), o.getNote(), lines);
    }

    private static PoLineResponse toLine(PurchaseOrderLine l) {
        return new PoLineResponse(l.getId(), l.getIngredientId(), l.getOrderedQty(),
                l.getReceivedQty(), MoneyDto.from(l.getUnitCost()));
    }
}
