package vn.essvn.erpcafe.organization.web;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
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
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.organization.application.BranchService;
import vn.essvn.erpcafe.organization.mapper.BranchMapper;

@RestController
@RequestMapping(ApiVersions.V1 + "/branches")
public class BranchController {

    private final BranchService branchService;
    private final BranchMapper branchMapper;

    public BranchController(BranchService branchService, BranchMapper branchMapper) {
        this.branchService = branchService;
        this.branchMapper = branchMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('branch:read')")
    public PageResponse<BranchResponse> list(Pageable pageable) {
        return PageResponse.from(branchService.list(pageable).map(branchMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('branch:read')")
    public BranchResponse get(@PathVariable UUID id) {
        return branchMapper.toResponse(branchService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('branch:write')")
    public ResponseEntity<BranchResponse> create(@Valid @RequestBody BranchRequest request) {
        BranchResponse body = branchMapper.toResponse(branchService.create(request));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/branches/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('branch:write')")
    public BranchResponse update(@PathVariable UUID id, @Valid @RequestBody BranchRequest request) {
        return branchMapper.toResponse(branchService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('branch:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        branchService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
