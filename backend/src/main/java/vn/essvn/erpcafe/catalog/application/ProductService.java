package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.api.CatalogApi;
import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.domain.ProductBranchAvailability;
import vn.essvn.erpcafe.catalog.persistence.CategoryRepository;
import vn.essvn.erpcafe.catalog.persistence.ModifierGroupRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ForbiddenException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.AuthPrincipal;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/** Product management: CRUD, modifier-group attachment, and per-branch availability/price. */
@Service
@Transactional
public class ProductService {

    private static final String CATALOG_READ = "catalog:read";
    private static final String CATALOG_WRITE = "catalog:write";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ModifierGroupRepository modifierGroupRepository;
    private final ProductBranchAvailabilityRepository availabilityRepository;
    private final OrganizationApi organizationApi;
    private final CurrentUser currentUser;
    private final CatalogApi catalogApi;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository,
            ModifierGroupRepository modifierGroupRepository,
            ProductBranchAvailabilityRepository availabilityRepository,
            OrganizationApi organizationApi, CurrentUser currentUser, CatalogApi catalogApi) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.modifierGroupRepository = modifierGroupRepository;
        this.availabilityRepository = availabilityRepository;
        this.organizationApi = organizationApi;
        this.currentUser = currentUser;
        this.catalogApi = catalogApi;
    }

    @Transactional(readOnly = true)
    public Page<Product> list(Pageable pageable) {
        return productRepository.findByCompanyId(currentUser.require().companyId(), pageable);
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        return productRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Product", id));
    }

    public Product create(UUID categoryId, String name, String sku, BigDecimal basePrice, String description) {
        UUID companyId = currentUser.require().companyId();
        requireCategory(companyId, categoryId);
        if (productRepository.existsByCompanyIdAndSku(companyId, sku)) {
            throw new ConflictException("Product SKU already exists: " + sku);
        }
        Product product = new Product(companyId, categoryId, name, sku, Money.of(basePrice));
        product.setDescription(description);
        return productRepository.save(product);
    }

    public Product update(UUID id, UUID categoryId, String name, String sku, BigDecimal basePrice,
            String description, Boolean active) {
        Product product = get(id);
        requireCategory(product.getCompanyId(), categoryId);
        if (!product.getSku().equals(sku)
                && productRepository.existsByCompanyIdAndSku(product.getCompanyId(), sku)) {
            throw new ConflictException("Product SKU already exists: " + sku);
        }
        product.setCategoryId(categoryId);
        product.setName(name);
        product.setSku(sku);
        product.setBasePrice(Money.of(basePrice));
        product.setDescription(description);
        if (active != null) {
            product.setActive(active);
        }
        return product;
    }

    public void delete(UUID id) {
        productRepository.delete(get(id));
    }

    /** Replaces the product's attached modifier groups with the given set (validated to the company). */
    public Product setModifierGroups(UUID productId, Set<UUID> groupIds) {
        Product product = get(productId);
        Set<ModifierGroup> groups = new HashSet<>();
        for (UUID groupId : groupIds) {
            ModifierGroup group = modifierGroupRepository.findByIdAndCompanyId(groupId, product.getCompanyId())
                    .orElseThrow(() -> ResourceNotFoundException.of("ModifierGroup", groupId));
            groups.add(group);
        }
        product.setModifierGroups(groups);
        return product;
    }

    /** The product's availability rows at the branches where the caller's role reads the catalog. */
    @Transactional(readOnly = true)
    public List<ProductBranchAvailability> availabilityOf(UUID productId) {
        get(productId);
        AuthPrincipal caller = currentUser.require();
        return availabilityRepository.findByProductId(productId).stream()
                .filter(a -> caller.permissionsAt(a.getBranchId()).contains(CATALOG_READ))
                .toList();
    }

    /** Sets (or updates) availability and optional price override for a product at a branch. */
    public ProductBranchAvailability setBranchAvailability(UUID productId, UUID branchId,
            boolean available, BigDecimal priceOverride) {
        Product product = get(productId);
        requireAtBranch(product.getCompanyId(), branchId, CATALOG_WRITE);
        Money override = priceOverride == null ? null : Money.of(priceOverride);
        return availabilityRepository.findByProductIdAndBranchId(productId, branchId)
                .map(existing -> {
                    existing.setAvailable(available);
                    existing.setPriceOverride(override);
                    return existing;
                })
                .orElseGet(() -> availabilityRepository.save(
                        new ProductBranchAvailability(productId, branchId, available, override)));
    }

    /** A product's price at a branch, which needs the catalog there; company-wide when no branch is named. */
    @Transactional(readOnly = true)
    public PricedLine price(UUID productId, UUID branchId, Set<UUID> modifierIds) {
        if (branchId != null) {
            requireAtBranch(currentUser.require().companyId(), branchId, CATALOG_READ);
        }
        return catalogApi.priceLine(branchId, productId, modifierIds);
    }

    /**
     * Another company's (or an inactive) branch is not found; a branch where the caller's role lacks the
     * permission is forbidden, whichever branch the request acts in (ADR-0007).
     */
    private void requireAtBranch(UUID companyId, UUID branchId, String permission) {
        if (!organizationApi.branchExistsInCompany(branchId, companyId)) {
            throw new ResourceNotFoundException("Branch not found or inactive: " + branchId);
        }
        if (!currentUser.require().permissionsAt(branchId).contains(permission)) {
            throw new ForbiddenException("You need " + permission + " at branch " + branchId);
        }
    }

    private void requireCategory(UUID companyId, UUID categoryId) {
        categoryRepository.findByIdAndCompanyId(categoryId, companyId)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", categoryId));
    }
}
