package vn.essvn.erpcafe.catalog.web;

import java.net.URI;
import java.util.List;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.catalog.application.PricingService;
import vn.essvn.erpcafe.catalog.application.ProductService;
import vn.essvn.erpcafe.catalog.domain.ProductBranchAvailability;
import vn.essvn.erpcafe.catalog.mapper.ProductMapper;
import vn.essvn.erpcafe.catalog.web.ProductDtos.BranchAvailabilityRequest;
import vn.essvn.erpcafe.catalog.web.ProductDtos.BranchAvailabilityResponse;
import vn.essvn.erpcafe.catalog.web.ProductDtos.CreateProductRequest;
import vn.essvn.erpcafe.catalog.web.ProductDtos.PriceResponse;
import vn.essvn.erpcafe.catalog.web.ProductDtos.ProductResponse;
import vn.essvn.erpcafe.catalog.web.ProductDtos.SetModifierGroupsRequest;
import vn.essvn.erpcafe.catalog.web.ProductDtos.UpdateProductRequest;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.common.web.PageResponse;

@RestController
@RequestMapping(ApiVersions.V1 + "/products")
public class ProductController {

    private final ProductService productService;
    private final PricingService pricingService;
    private final ProductMapper productMapper;

    public ProductController(ProductService productService, PricingService pricingService,
            ProductMapper productMapper) {
        this.productService = productService;
        this.pricingService = pricingService;
        this.productMapper = productMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public PageResponse<ProductResponse> list(Pageable pageable) {
        return PageResponse.from(productService.list(pageable).map(productMapper::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:read')")
    public ProductResponse get(@PathVariable UUID id) {
        return productMapper.toResponse(productService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse body = productMapper.toResponse(productService.create(
                request.categoryId(), request.name(), request.sku(), request.basePrice(), request.description()));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/products/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return productMapper.toResponse(productService.update(id, request.categoryId(), request.name(),
                request.sku(), request.basePrice(), request.description(), request.active()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/modifier-groups")
    @PreAuthorize("hasAuthority('catalog:write')")
    public ProductResponse setModifierGroups(@PathVariable UUID id,
            @Valid @RequestBody SetModifierGroupsRequest request) {
        return productMapper.toResponse(productService.setModifierGroups(id, request.modifierGroupIds()));
    }

    @GetMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('catalog:read')")
    public List<BranchAvailabilityResponse> availability(@PathVariable UUID id) {
        return productService.availabilityOf(id).stream().map(this::toAvailability).toList();
    }

    @PutMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('catalog:write')")
    public BranchAvailabilityResponse setAvailability(@PathVariable UUID id,
            @Valid @RequestBody BranchAvailabilityRequest request) {
        return toAvailability(productService.setBranchAvailability(
                id, request.branchId(), request.available(), request.priceOverride()));
    }

    @GetMapping("/{id}/price")
    @PreAuthorize("hasAuthority('catalog:read')")
    public PriceResponse price(@PathVariable UUID id,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) Set<UUID> modifierIds) {
        return new PriceResponse(id, branchId,
                MoneyDto.from(pricingService.priceOf(id, branchId, modifierIds)));
    }

    private BranchAvailabilityResponse toAvailability(ProductBranchAvailability a) {
        return new BranchAvailabilityResponse(a.getBranchId(), a.isAvailable(),
                MoneyDto.from(a.getPriceOverride()));
    }
}
