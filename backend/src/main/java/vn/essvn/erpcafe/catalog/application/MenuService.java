package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
import vn.essvn.erpcafe.inventory.api.ConsumptionResolver;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Class: MenuService
 * Purpose: Lists what the active branch sells, priced by the same rule the till charges.
 */
@Service
@Transactional(readOnly = true)
public class MenuService {

    /**
     * A product on the branch menu, the price it is sold at there, and whether the branch has the stock
     * to make one of it, with no modifiers, now.
     */
    public record MenuEntry(Product product, Money effectivePrice, boolean available) {
    }

    private final ProductRepository productRepository;
    private final ProductBranchAvailabilityRepository availabilityRepository;
    private final OrganizationApi organizationApi;
    private final ConsumptionResolver consumption;
    private final InventoryApi inventoryApi;

    public MenuService(ProductRepository productRepository, ProductBranchAvailabilityRepository availabilityRepository,
            OrganizationApi organizationApi, ConsumptionResolver consumption, InventoryApi inventoryApi) {
        this.productRepository = productRepository;
        this.availabilityRepository = availabilityRepository;
        this.organizationApi = organizationApi;
        this.consumption = consumption;
        this.inventoryApi = inventoryApi;
    }

    public List<MenuEntry> menu() {
        UUID companyId = TenantContext.require();
        UUID branchId = BranchContext.require();
        if (!organizationApi.branchExistsInCompany(companyId, branchId)) {
            throw ResourceNotFoundException.of("Branch", branchId);
        }
        Map<UUID, ProductBranchAvailability> byProduct = availabilityRepository.findByBranchId(branchId).stream()
                .collect(Collectors.toMap(ProductBranchAvailability::getProductId, Function.identity()));
        List<Product> sold = productRepository.findByActiveTrueOrderByName().stream()
                .filter(p -> byProduct.get(p.getId()) == null || byProduct.get(p.getId()).isAvailable())
                .toList();
        // a product without a recipe consumes nothing, so it is never out of stock
        Map<UUID, List<StockLine>> oneOfEach = consumption.oneOfEach(companyId, sold.stream().map(Product::getId).toList());
        Set<UUID> outOfStock = oneOfEach.isEmpty() ? Set.of() : inventoryApi.unavailable(branchId, oneOfEach);
        return sold.stream()
                .map(p -> new MenuEntry(p, p.priceAt(byProduct.get(p.getId())), !outOfStock.contains(p.getId())))
                .toList();
    }
}
