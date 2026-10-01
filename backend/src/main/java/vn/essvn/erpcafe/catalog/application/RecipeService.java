package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Recipe;
import vn.essvn.erpcafe.catalog.domain.RecipeLine;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;

/**
 * Manages a product's recipe. Setting a recipe replaces all of its lines
 * (upsert semantics). Every line is validated against the company's ingredients
 * and units before anything is stored.
 */
@Service
@Transactional
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final ProductRepository productRepository;
    private final InventoryApi inventoryApi;

    public RecipeService(RecipeRepository recipeRepository, ProductRepository productRepository,
            InventoryApi inventoryApi) {
        this.inventoryApi = inventoryApi;
        this.recipeRepository = recipeRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public Recipe getForProduct(UUID productId) {
        requireProduct(productId);
        return recipeRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No recipe for product: " + productId));
    }

    /** Creates or replaces the recipe for a product. */
    public Recipe setRecipe(UUID productId, List<ConsumptionLine> lines, Long expectedVersion) {
        requireProduct(productId);
        inventoryApi.validateConsumption(TenantContext.require(), lines.stream()
                .map(l -> new StockLine(l.ingredientId(), l.quantity(), l.unit())).toList());
        Recipe recipe = recipeRepository.findByProductId(productId).orElseGet(() -> new Recipe(productId));
        requireCurrentVersion(recipe.getVersion(), expectedVersion);
        List<RecipeLine> recipeLines = lines.stream()
                .map(l -> new RecipeLine(l.ingredientId(), l.quantity(), l.unit()))
                .toList();
        recipe.replaceLines(recipeLines);
        return recipeRepository.save(recipe);
    }

    // Same stale-form guard as IngredientService: enforced only when the client sends a version.
    private static void requireCurrentVersion(Long current, Long expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ConflictException(ConflictException.STALE_EDIT_MESSAGE);
        }
    }

    private void requireProduct(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw ResourceNotFoundException.of("Product", productId);
        }
    }
}
