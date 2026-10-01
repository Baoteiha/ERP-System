package vn.essvn.erpcafe.organization.web;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import vn.essvn.erpcafe.organization.application.CompanyService;
import vn.essvn.erpcafe.organization.mapper.CompanyMapper;

@RestController
@RequestMapping(ApiVersions.V1 + "/companies")
public class CompanyController {

    private final CompanyService companyService;
    private final CompanyMapper companyMapper;

    public CompanyController(CompanyService companyService, CompanyMapper companyMapper) {
        this.companyService = companyService;
        this.companyMapper = companyMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('company:read')")
    public PageResponse<CompanyResponse> list(Pageable pageable) {
        return PageResponse.from(companyService.list(pageable).map(companyMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('company:read')")
    public CompanyResponse get(@PathVariable UUID id) {
        return companyMapper.toResponse(companyService.get(id));
    }

    @PostMapping
    @PreAuthorize("denyAll()")
    public ResponseEntity<Void> create() {
        // Companies are provisioned by the platform, never by a tenant; kept only so the verb answers 403, not 405.
        throw new AccessDeniedException("Companies cannot be created by a tenant");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('company:write')")
    public CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody CompanyRequest request) {
        return companyMapper.toResponse(companyService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('company:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        companyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
