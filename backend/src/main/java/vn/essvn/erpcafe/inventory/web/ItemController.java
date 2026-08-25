package vn.essvn.erpcafe.inventory.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.inventory.application.ItemService;
import vn.essvn.erpcafe.inventory.mapper.ItemMapper;
import vn.essvn.erpcafe.inventory.web.ItemDtos.ItemRequest;
import vn.essvn.erpcafe.inventory.web.ItemDtos.ItemResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/items")
public class ItemController {

    private final ItemService itemService;
    private final ItemMapper itemMapper;

    public ItemController(ItemService itemService, ItemMapper itemMapper) {
        this.itemService = itemService;
        this.itemMapper = itemMapper;
    }

    /**
     * Lists items, optionally narrowed to one supplier's catalogue (what a purchase order
     * may draw from) or to one ingredient (every way it can be bought).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<ItemResponse> list(@RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID ingredientId) {
        return itemService.list(supplierId, ingredientId).stream().map(itemMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:read')")
    public ItemResponse get(@PathVariable UUID id) {
        return itemMapper.toResponse(itemService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody ItemRequest request) {
        ItemResponse body = itemMapper.toResponse(itemService.create(
                request.sku(), request.name(), request.supplierId(), request.ingredientId(), request.unit()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/items/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public ItemResponse update(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        boolean active = request.active() == null || request.active();
        return itemMapper.toResponse(itemService.update(
                id, request.sku(), request.name(), request.supplierId(), request.ingredientId(),
                request.unit(), active, request.version()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        itemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
