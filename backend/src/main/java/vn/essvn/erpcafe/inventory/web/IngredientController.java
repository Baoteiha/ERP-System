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
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.inventory.application.IngredientService;
import vn.essvn.erpcafe.inventory.mapper.IngredientMapper;
import vn.essvn.erpcafe.inventory.web.IngredientDtos.IngredientRequest;
import vn.essvn.erpcafe.inventory.web.IngredientDtos.IngredientResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/ingredients")
public class IngredientController {

    private final IngredientService ingredientService;
    private final IngredientMapper ingredientMapper;

    public IngredientController(IngredientService ingredientService, IngredientMapper ingredientMapper) {
        this.ingredientService = ingredientService;
        this.ingredientMapper = ingredientMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<IngredientResponse> list() {
        return ingredientService.list().stream().map(ingredientMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:read')")
    public IngredientResponse get(@PathVariable UUID id) {
        return ingredientMapper.toResponse(ingredientService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<IngredientResponse> create(@Valid @RequestBody IngredientRequest request) {
        IngredientResponse body = ingredientMapper.toResponse(
                ingredientService.create(request.name(), request.baseUnit(), request.category()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/ingredients/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public IngredientResponse update(@PathVariable UUID id, @Valid @RequestBody IngredientRequest request) {
        boolean active = request.active() == null || request.active();
        return ingredientMapper.toResponse(
                ingredientService.update(id, request.name(), request.baseUnit(), request.category(), active,
                        request.version()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ingredientService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
