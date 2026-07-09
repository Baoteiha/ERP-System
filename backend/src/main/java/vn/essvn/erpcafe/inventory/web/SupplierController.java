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
import vn.essvn.erpcafe.inventory.application.SupplierService;
import vn.essvn.erpcafe.inventory.mapper.SupplierMapper;
import vn.essvn.erpcafe.inventory.web.SupplierDtos.SupplierRequest;
import vn.essvn.erpcafe.inventory.web.SupplierDtos.SupplierResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/suppliers")
public class SupplierController {

    private final SupplierService supplierService;
    private final SupplierMapper supplierMapper;

    public SupplierController(SupplierService supplierService, SupplierMapper supplierMapper) {
        this.supplierService = supplierService;
        this.supplierMapper = supplierMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:read')")
    public List<SupplierResponse> list() {
        return supplierService.list().stream().map(supplierMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:read')")
    public SupplierResponse get(@PathVariable UUID id) {
        return supplierMapper.toResponse(supplierService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        SupplierResponse body = supplierMapper.toResponse(supplierService.create(
                request.name(), request.contactPhone(), request.contactEmail(), request.address()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/suppliers/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public SupplierResponse update(@PathVariable UUID id, @Valid @RequestBody SupplierRequest request) {
        boolean active = request.active() == null || request.active();
        return supplierMapper.toResponse(supplierService.update(id, request.name(),
                request.contactPhone(), request.contactEmail(), request.address(), active));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
