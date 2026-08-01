package vn.essvn.erpcafe.identity.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.identity.application.UserService;
import vn.essvn.erpcafe.identity.domain.UserBranchAccess;
import vn.essvn.erpcafe.identity.mapper.UserMapper;
import vn.essvn.erpcafe.identity.web.UserDtos.BranchAccessResponse;
import vn.essvn.erpcafe.identity.web.UserDtos.CreateUserRequest;
import vn.essvn.erpcafe.identity.web.UserDtos.GrantAccessRequest;
import vn.essvn.erpcafe.identity.web.UserDtos.UserResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/users")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;

    public UserController(UserService userService, UserMapper userMapper) {
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    public PageResponse<UserResponse> list(Pageable pageable) {
        return PageResponse.from(userService.list(pageable).map(userMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public UserResponse get(@PathVariable UUID id) {
        return userMapper.toResponse(userService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:write')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse body = userMapper.toResponse(
                userService.create(request.email(), request.password(), request.fullName()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/users/" + body.id())).body(body);
    }

    @GetMapping("/{id}/access")
    @PreAuthorize("hasAuthority('user:read')")
    public List<BranchAccessResponse> access(@PathVariable UUID id) {
        return userService.accessOf(id).stream()
                .map(a -> new BranchAccessResponse(a.getBranchId(), a.getRoleId()))
                .toList();
    }

    @PostMapping("/{id}/access")
    @PreAuthorize("hasAuthority('user:write')")
    public BranchAccessResponse grant(@PathVariable UUID id, @Valid @RequestBody GrantAccessRequest request) {
        UserBranchAccess granted = userService.grantAccess(id, request.branchId(), request.roleId());
        return new BranchAccessResponse(granted.getBranchId(), granted.getRoleId());
    }

    @DeleteMapping("/{id}/access/{branchId}")
    @PreAuthorize("hasAuthority('user:write')")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, @PathVariable UUID branchId) {
        userService.revokeAccess(id, branchId);
        return ResponseEntity.noContent().build();
    }
}
