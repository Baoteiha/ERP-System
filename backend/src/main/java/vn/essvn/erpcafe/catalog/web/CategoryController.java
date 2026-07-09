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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.catalog.application.CategoryService;
import vn.essvn.erpcafe.catalog.mapper.CategoryMapper;
import vn.essvn.erpcafe.catalog.web.CategoryDtos.CategoryRequest;
import vn.essvn.erpcafe.catalog.web.CategoryDtos.CategoryResponse;
import vn.essvn.erpcafe.common.web.ApiVersions;

@RestController
@RequestMapping(ApiVersions.V1 + "/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;

    public CategoryController(CategoryService categoryService, CategoryMapper categoryMapper) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public List<CategoryResponse> list() {
        return categoryService.list().stream().map(categoryMapper::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:read')")
    public CategoryResponse get(@PathVariable UUID id) {
        return categoryMapper.toResponse(categoryService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse body = categoryMapper.toResponse(
                categoryService.create(request.name(), request.displayOrder()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/categories/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:write')")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        boolean active = request.active() == null || request.active();
        return categoryMapper.toResponse(
                categoryService.update(id, request.name(), request.displayOrder(), active));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
