package vn.essvn.erpcafe.identity.web;

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
import vn.essvn.erpcafe.identity.application.RoleService;
import vn.essvn.erpcafe.identity.mapper.RoleMapper;
import vn.essvn.erpcafe.identity.web.RoleDtos.CreateRoleRequest;
import vn.essvn.erpcafe.identity.web.RoleDtos.PermissionsRequest;
import vn.essvn.erpcafe.identity.web.RoleDtos.RoleResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/roles")
public class RoleController {

    private final RoleService roleService;
    private final RoleMapper roleMapper;

    public RoleController(RoleService roleService, RoleMapper roleMapper) {
        this.roleService = roleService;
        this.roleMapper = roleMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('role:read')")
    public List<RoleResponse> list() {
        return roleService.listForCurrentCompany().stream().map(roleMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:read')")
    public RoleResponse get(@PathVariable UUID id) {
        return roleMapper.toResponse(roleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role:write')")
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse body = roleMapper.toResponse(
                roleService.create(request.name(), request.description(), request.permissions()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/roles/" + body.id())).body(body);
    }

    /** Replaces the role's entire permission set. */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:write')")
    public RoleResponse replacePermissions(@PathVariable UUID id, @Valid @RequestBody PermissionsRequest request) {
        return roleMapper.toResponse(roleService.replacePermissions(id, request.permissions()));
    }

    /** Adds permissions to the role (keeps existing ones). */
    @PostMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:write')")
    public RoleResponse addPermissions(@PathVariable UUID id, @Valid @RequestBody PermissionsRequest request) {
        return roleMapper.toResponse(roleService.addPermissions(id, request.permissions()));
    }

    /** Removes a single permission from the role. */
    @DeleteMapping("/{id}/permissions/{permission}")
    @PreAuthorize("hasAuthority('role:write')")
    public RoleResponse removePermission(@PathVariable UUID id, @PathVariable String permission) {
        return roleMapper.toResponse(roleService.removePermission(id, permission));
    }
}
