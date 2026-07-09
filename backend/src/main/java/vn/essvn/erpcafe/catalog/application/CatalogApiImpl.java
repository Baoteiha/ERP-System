package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.api.CatalogApi;
import vn.essvn.erpcafe.catalog.api.IngredientConsumption;
import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.catalog.api.PricedLine.PricedModifier;
import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.Product;
import vn.essvn.erpcafe.catalog.domain.Recipe;
import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;

/**
 * Implements the published {@link CatalogApi}. Runs inside a read-only
 * transaction so it can traverse lazy recipe/modifier collections.
 */
@Service
@Transactional(readOnly = true)
public class CatalogApiImpl implements CatalogApi {

    private final ProductRepository productRepository;
    private final RecipeRepository recipeRepository;
    private final ModifierRepository modifierRepository;
    private final PricingService pricingService;

    public CatalogApiImpl(ProductRepository productRepository, RecipeRepository recipeRepository,
            ModifierRepository modifierRepository, PricingService pricingService) {
        this.productRepository = productRepository;
        this.recipeRepository = recipeRepository;
        this.modifierRepository = modifierRepository;
        this.pricingService = pricingService;
    }

    @Override
    public boolean productExists(UUID productId) {
        return productRepository.existsById(productId);
    }

    @Override
    public Money priceOf(UUID productId, UUID branchId, Set<UUID> modifierIds) {
        return pricingService.priceOf(productId, branchId, modifierIds);
    }

    @Override
    public List<IngredientConsumption> explode(UUID productId, int quantity, Set<UUID> modifierIds) {
        Recipe recipe = recipeRepository.findByProductId(productId).orElse(null);
        if (recipe == null) {
            return List.of();
        }
        BigDecimal qty = BigDecimal.valueOf(quantity);

        // Accumulate net consumption per (ingredient, unit). We key on unit too because unit
        // conversion (g<->kg) isn't handled yet — same ingredient in different units stays separate.
        Map<String, IngredientConsumption> acc = new LinkedHashMap<>();
        // Base recipe: each line scaled by the number of units ordered.
        recipe.getLines().forEach(line ->
                add(acc, line.getIngredientId(), line.getQuantity().multiply(qty), line.getUnit()));

        // Selected modifiers layer signed deltas on top (extra shot = +coffee; oat = -dairy, +oat).
        if (modifierIds != null) {
            for (UUID modifierId : modifierIds) {
                Modifier modifier = modifierRepository.findById(modifierId).orElse(null);
                if (modifier == null) {
                    continue; // ignore unknown modifier ids rather than fail the whole explosion
                }
                modifier.getRecipeLines().forEach(delta ->
                        add(acc, delta.getIngredientId(), delta.getQuantityDelta().multiply(qty), delta.getUnit()));
            }
        }
        return new ArrayList<>(acc.values());
    }

    @Override
    public PricedLine priceLine(UUID productId, UUID branchId, Set<UUID> modifierIds) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));
        // Base = branch override or company base price, WITHOUT modifiers (empty set).
        Money base = pricingService.priceOf(productId, branchId, Set.of());

        List<PricedModifier> modifiers = new ArrayList<>();
        Money unitTotal = base;
        if (modifierIds != null) {
            for (UUID modifierId : modifierIds) {
                Modifier m = modifierRepository.findById(modifierId)
                        .orElseThrow(() -> ResourceNotFoundException.of("Modifier", modifierId));
                modifiers.add(new PricedModifier(m.getId(), m.getName(), m.getPriceDelta()));
                unitTotal = unitTotal.add(m.getPriceDelta());
            }
        }
        return new PricedLine(productId, product.getName(), base, modifiers, unitTotal);
    }

    @Override
    public void validateModifierSelection(UUID productId, Set<UUID> modifierIds) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ResourceNotFoundException.of("Product", productId));
        Set<UUID> selected = modifierIds == null ? Set.of() : new HashSet<>(modifierIds);
        Set<UUID> unaccounted = new HashSet<>(selected);

        for (ModifierGroup group : product.getModifierGroups()) {
            Set<UUID> groupModifierIds = group.getModifiers().stream()
                    .map(Modifier::getId).collect(Collectors.toSet());
            long chosen = selected.stream().filter(groupModifierIds::contains).count();
            if (chosen < group.getMinSelect() || chosen > group.getMaxSelect()) {
                throw new BusinessRuleException(
                        "Group '%s' requires between %d and %d selections (got %d)"
                                .formatted(group.getName(), group.getMinSelect(), group.getMaxSelect(), chosen));
            }
            unaccounted.removeAll(groupModifierIds);
        }
        if (!unaccounted.isEmpty()) {
            throw new BusinessRuleException("Modifiers not offered by this product: " + unaccounted);
        }
    }

    private void add(Map<String, IngredientConsumption> acc, UUID ingredientId, BigDecimal quantity, String unit) {
        String key = ingredientId + "|" + unit;
        acc.merge(key, new IngredientConsumption(ingredientId, quantity, unit),
                (existing, incoming) -> new IngredientConsumption(
                        ingredientId, existing.quantity().add(incoming.quantity()), unit));
    }
}
