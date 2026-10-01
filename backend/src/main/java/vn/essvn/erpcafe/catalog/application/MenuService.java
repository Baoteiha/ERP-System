package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.domain.ProductBranchAvailability;
import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.BranchContext;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: MenuService
 * Purpose: Lists what the active branch sells, priced by the same rule the till charges.
 */
@Service
@Transactional(readOnly = true)
public class MenuService {

    /** A product on the branch menu together with the price it is sold at there. */
    public record MenuEntry(Product product, Money effectivePrice) {
    }

    private final ProductRepository productRepository;
    private final ProductBranchAvailabilityRepository availabilityRepository;
    private final OrganizationApi organizationApi;

    public MenuService(ProductRepository productRepository, ProductBranchAvailabilityRepository availabilityRepository,
            OrganizationApi organizationApi) {
        this.productRepository = productRepository;
        this.availabilityRepository = availabilityRepository;
        this.organizationApi = organizationApi;
    }

    public List<MenuEntry> menu() {
        UUID branchId = BranchContext.require();
        if (!organizationApi.branchExistsInCompany(branchId, TenantContext.require())) {
            throw ResourceNotFoundException.of("Branch", branchId);
        }
        Map<UUID, ProductBranchAvailability> byProduct = availabilityRepository.findByBranchId(branchId).stream()
                .collect(Collectors.toMap(ProductBranchAvailability::getProductId, Function.identity()));
        return productRepository.findByActiveTrueOrderByName().stream()
                .filter(p -> byProduct.get(p.getId()) == null || byProduct.get(p.getId()).isAvailable())
                .map(p -> new MenuEntry(p, p.priceAt(byProduct.get(p.getId()))))
                .toList();
    }
}
