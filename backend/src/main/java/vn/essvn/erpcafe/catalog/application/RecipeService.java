package vn.essvn.erpcafe.catalog.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Recipe;
import vn.essvn.erpcafe.catalog.domain.RecipeLine;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;

/**
 * Manages a product's recipe. Setting a recipe replaces all of its lines
 * (upsert semantics). Ingredient ids are stored as-is; they'll be validated
 * against real ingredients once the inventory module exists.
 */
@Service
@Transactional
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final ProductRepository productRepository;

    public RecipeService(RecipeRepository recipeRepository, ProductRepository productRepository) {
        this.recipeRepository = recipeRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public Recipe getForProduct(UUID productId) {
        return recipeRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No recipe for product: " + productId));
    }

    /** Creates or replaces the recipe for a product. */
    public Recipe setRecipe(UUID productId, List<ConsumptionLine> lines) {
        if (!productRepository.existsById(productId)) {
            throw ResourceNotFoundException.of("Product", productId);
        }
        Recipe recipe = recipeRepository.findByProductId(productId).orElseGet(() -> new Recipe(productId));
        List<RecipeLine> recipeLines = lines.stream()
                .map(l -> new RecipeLine(l.ingredientId(), l.quantity(), l.unit()))
                .toList();
        recipe.replaceLines(recipeLines);
        return recipeRepository.save(recipe);
    }
}
