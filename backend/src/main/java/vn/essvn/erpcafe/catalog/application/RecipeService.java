package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.ConsumptionLine;
import vn.essvn.erpcafe.catalog.domain.Recipe;
import vn.essvn.erpcafe.catalog.domain.RecipeVersion;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeVersionRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;

/**
 * Manages a product's recipe. Saving a recipe adds its next version, in force from
 * then on, and leaves the earlier ones as they were. Every line is validated against
 * the company's ingredients and units before anything is stored.
 */
@Service
@Transactional
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final RecipeVersionRepository versionRepository;
    private final ProductRepository productRepository;
    private final InventoryApi inventoryApi;

    public RecipeService(RecipeRepository recipeRepository, RecipeVersionRepository versionRepository,
            ProductRepository productRepository, InventoryApi inventoryApi) {
        this.inventoryApi = inventoryApi;
        this.recipeRepository = recipeRepository;
        this.versionRepository = versionRepository;
        this.productRepository = productRepository;
    }

    /** The version in force of the product's recipe. */
    @Transactional(readOnly = true)
    public RecipeVersion getForProduct(UUID productId) {
        requireProduct(productId);
        return versionRepository.findCurrent(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No recipe for product: " + productId));
    }

    /**
     * Saves the lines as the product's next recipe version. {@code expectedVersion} is the version the
     * client edited; when given, it must still be the one in force.
     */
    public RecipeVersion setRecipe(UUID productId, List<ConsumptionInput> lines, Long expectedVersion) {
        requireProduct(productId);
        inventoryApi.validateConsumption(TenantContext.require(), lines.stream()
                .map(l -> new StockLine(l.ingredientId(), l.quantity(), l.unit())).toList());
        // A managed recipe persists its new version at flush; merging it (save) would copy the version instead.
        Recipe recipe = recipeRepository.findByProductId(productId)
                .orElseGet(() -> recipeRepository.save(new Recipe(productId)));
        ConflictException.requireCurrentVersion(recipe.getCurrentVersionNo(), expectedVersion);
        return recipe.revise(lines.stream()
                .map(l -> new ConsumptionLine(l.ingredientId(), l.quantity(), Unit.parse(l.unit())))
                .toList());
    }

    private void requireProduct(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw ResourceNotFoundException.of("Product", productId);
        }
    }
}
