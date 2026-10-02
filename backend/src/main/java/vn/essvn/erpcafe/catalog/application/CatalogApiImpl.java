package vn.essvn.erpcafe.catalog.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.api.CatalogApi;
import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.catalog.api.PricedLine.PricedModifier;
import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.domain.ProductBranchAvailability;
import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.organization.api.OrganizationApi;

/**
 * Implements the published {@link CatalogApi}. Runs inside a read-only
 * transaction so it can traverse lazy modifier collections.
 */
@Service
@Transactional(readOnly = true)
public class CatalogApiImpl implements CatalogApi {

    private final ProductRepository productRepository;
    private final ProductBranchAvailabilityRepository availabilityRepository;
    private final OrganizationApi organizationApi;

    public CatalogApiImpl(ProductRepository productRepository,
            ProductBranchAvailabilityRepository availabilityRepository, OrganizationApi organizationApi) {
        this.productRepository = productRepository;
        this.availabilityRepository = availabilityRepository;
        this.organizationApi = organizationApi;
    }

    @Override
    public PricedLine priceLine(UUID branchId, UUID productId, Set<UUID> modifierIds) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));
        // No branch means a company-wide quote (catalog browsing): base price, no availability rules.
        if (branchId != null && !organizationApi.branchExistsInCompany(TenantContext.require(), branchId)) {
            throw ResourceNotFoundException.of("Branch", branchId);
        }

        if (!product.isActive()) {
            throw new BusinessRuleException("Product is inactive: " + product.getName());
        }
        ProductBranchAvailability availability = branchId == null ? null
                : availabilityRepository.findByProductIdAndBranchId(productId, branchId).orElse(null);
        if (availability != null && !availability.isAvailable()) {
            throw new BusinessRuleException("Product is not available at this branch: " + product.getName());
        }

        List<Modifier> chosen = selectModifiers(product, modifierIds);
        Money base = product.priceAt(availability);
        List<PricedModifier> modifiers = new ArrayList<>();
        Money unitTotal = base;
        for (Modifier m : chosen) {
            modifiers.add(new PricedModifier(m.getId(), m.getName(), m.getPriceDelta()));
            unitTotal = unitTotal.add(m.getPriceDelta());
        }
        return new PricedLine(productId, product.getName(), base, modifiers, unitTotal);
    }

    // Resolves the selection against the product's own groups, so an id from anywhere else
    // (another product's group, another company) is simply "not offered" and never leaks existence.
    private List<Modifier> selectModifiers(Product product, Set<UUID> modifierIds) {
        Set<UUID> selected = modifierIds == null ? Set.of() : modifierIds;
        List<Modifier> chosen = new ArrayList<>();
        Set<UUID> unaccounted = new HashSet<>(selected);

        for (ModifierGroup group : product.getModifierGroups()) {
            List<Modifier> inGroup = group.getModifiers().stream()
                    .filter(m -> selected.contains(m.getId())).toList();
            inGroup.stream().filter(m -> !m.isActive()).findFirst().ifPresent(m -> {
                throw new BusinessRuleException("Modifier is inactive: " + m.getName());
            });
            if (inGroup.size() < group.getMinSelect() || inGroup.size() > group.getMaxSelect()) {
                throw new BusinessRuleException(
                        "Group '%s' requires between %d and %d selections (got %d)"
                                .formatted(group.getName(), group.getMinSelect(), group.getMaxSelect(), inGroup.size()));
            }
            inGroup.forEach(m -> unaccounted.remove(m.getId()));
            chosen.addAll(inGroup);
        }
        if (!unaccounted.isEmpty()) {
            throw new BusinessRuleException("Modifiers not offered by this product: " + unaccounted);
        }
        return chosen;
    }
}
