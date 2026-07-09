package vn.essvn.erpcafe.catalog.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductBranchAvailabilityRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;

/**
 * Computes a product's unit price: the branch price override (if set) or the
 * base price, plus the price deltas of the selected modifiers.
 */
@Service
@Transactional(readOnly = true)
public class PricingService {

    private final ProductRepository productRepository;
    private final ProductBranchAvailabilityRepository availabilityRepository;
    private final ModifierRepository modifierRepository;

    public PricingService(ProductRepository productRepository,
            ProductBranchAvailabilityRepository availabilityRepository,
            ModifierRepository modifierRepository) {
        this.productRepository = productRepository;
        this.availabilityRepository = availabilityRepository;
        this.modifierRepository = modifierRepository;
    }

    public Money priceOf(UUID productId, UUID branchId, Set<UUID> modifierIds) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));

        Money price = basePriceFor(product, branchId);
        if (modifierIds != null) {
            for (UUID modifierId : modifierIds) {
                Modifier modifier = modifierRepository.findById(modifierId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Modifier", modifierId));
                price = price.add(modifier.getPriceDelta());
            }
        }
        return price;
    }

    // A branch's price override wins over the company-wide base price; falls back to base when
    // no branch is given or the branch has no override. Modifier deltas are added on top by the caller.
    private Money basePriceFor(Product product, UUID branchId) {
        if (branchId != null) {
            var override = availabilityRepository.findByProductIdAndBranchId(product.getId(), branchId)
                    .map(a -> a.getPriceOverride())
                    .orElse(null);
            if (override != null) {
                return override;
            }
        }
        return product.getBasePrice();
    }
}
