package vn.essvn.erpcafe.inventory.web;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.inventory.application.StockService;
import vn.essvn.erpcafe.inventory.domain.StockItem;
import vn.essvn.erpcafe.inventory.domain.StockMovement;
import vn.essvn.erpcafe.inventory.web.StockDtos.AdjustRequest;
import vn.essvn.erpcafe.inventory.web.StockDtos.MovementResponse;
import vn.essvn.erpcafe.inventory.web.StockDtos.ReorderLevelRequest;
import vn.essvn.erpcafe.inventory.web.StockDtos.StockItemResponse;
import vn.essvn.erpcafe.inventory.web.StockDtos.WasteRequest;

/**
 * Stock for the active branch (requires the {@code X-Branch-Id} header):
 * on-hand levels, the movement ledger, adjustments, waste, reorder levels, and
 * low-stock alerts.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<StockItemResponse> list() {
        return stockService.listStock().stream().map(StockController::toStockItem).toList();
    }

    @GetMapping("/low")
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<StockItemResponse> lowStock() {
        return stockService.lowStock().stream().map(StockController::toStockItem).toList();
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('inventory:read')")
    public PageResponse<MovementResponse> movements(
            @RequestParam(required = false) UUID ingredientId, Pageable pageable) {
        return PageResponse.from(stockService.movements(ingredientId, pageable).map(StockController::toMovement));
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasAuthority('inventory:write')")
    public StockItemResponse adjust(@Valid @RequestBody AdjustRequest request) {
        return toStockItem(stockService.adjust(request.ingredientId(), request.quantityDelta(), request.note()));
    }

    @PostMapping("/waste")
    @PreAuthorize("hasAuthority('inventory:write')")
    public StockItemResponse waste(@Valid @RequestBody WasteRequest request) {
        return toStockItem(stockService.waste(request.ingredientId(), request.quantity(), request.note()));
    }

    @PutMapping("/reorder-level")
    @PreAuthorize("hasAuthority('inventory:write')")
    public StockItemResponse setReorderLevel(@Valid @RequestBody ReorderLevelRequest request) {
        return toStockItem(stockService.setReorderLevel(request.ingredientId(), request.reorderLevel()));
    }

    private static StockItemResponse toStockItem(StockItem s) {
        return new StockItemResponse(s.getIngredientId(), s.getBranchId(), s.getQuantityOnHand(),
                s.getReorderLevel(), MoneyDto.from(s.getAvgUnitCost()), s.isBelowReorderLevel());
    }

    private static MovementResponse toMovement(StockMovement m) {
        return new MovementResponse(m.getId(), m.getIngredientId(), m.getBranchId(), m.getType().name(),
                m.getQuantity(), MoneyDto.from(m.getUnitCost()), m.getRefType(), m.getRefId(), m.getNote(),
                m.getCreatedAt(), m.getCreatedBy());
    }
}
