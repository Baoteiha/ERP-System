package vn.essvn.erpcafe.catalog.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.catalog.application.ConsumptionLine;
import vn.essvn.erpcafe.catalog.application.ModifierService;
import vn.essvn.erpcafe.catalog.mapper.ModifierMapper;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.AddModifierRequest;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.CreateModifierGroupRequest;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.ModifierGroupResponse;
import vn.essvn.erpcafe.catalog.web.ModifierDtos.ModifierResponse;
import vn.essvn.erpcafe.common.web.ApiVersions;

@RestController
@RequestMapping(ApiVersions.V1 + "/modifier-groups")
public class ModifierGroupController {

    private final ModifierService modifierService;
    private final ModifierMapper modifierMapper;

    public ModifierGroupController(ModifierService modifierService, ModifierMapper modifierMapper) {
        this.modifierService = modifierService;
        this.modifierMapper = modifierMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public List<ModifierGroupResponse> list() {
        return modifierService.listGroups().stream().map(modifierMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ModifierGroupResponse get(@PathVariable UUID id) {
        return modifierMapper.toResponse(modifierService.getGroup(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<ModifierGroupResponse> create(@Valid @RequestBody CreateModifierGroupRequest request) {
        ModifierGroupResponse body = modifierMapper.toResponse(
                modifierService.createGroup(request.name(), request.minSelect(), request.maxSelect()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/modifier-groups/" + body.id())).body(body);
    }

    @PostMapping("/{id}/modifiers")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ModifierResponse addModifier(@PathVariable UUID id, @Valid @RequestBody AddModifierRequest request) {
        List<ConsumptionLine> deltas = request.recipeDeltas() == null ? List.of()
                : request.recipeDeltas().stream()
                        .map(l -> new ConsumptionLine(l.ingredientId(), l.quantity(), l.unit()))
                        .toList();
        return modifierMapper.toResponse(
                modifierService.addModifier(id, request.name(), request.priceDelta(), request.displayOrder(), deltas));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        modifierService.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }
}
