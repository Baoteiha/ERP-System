package vn.essvn.erpcafe.catalog.application;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeRepository;
import vn.essvn.erpcafe.inventory.api.IngredientReferenceCheck;

/**
 * Class: CatalogIngredientReferenceCheck
 * Purpose: Reports an ingredient as in use while a product recipe line or modifier delta of the company still names it.
 */
@Component
public class CatalogIngredientReferenceCheck implements IngredientReferenceCheck {

    private final RecipeRepository recipeRepository;
    private final ModifierRepository modifierRepository;

    public CatalogIngredientReferenceCheck(RecipeRepository recipeRepository, ModifierRepository modifierRepository) {
        this.recipeRepository = recipeRepository;
        this.modifierRepository = modifierRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isReferenced(UUID companyId, UUID ingredientId) {
        return recipeRepository.existsLineForIngredient(companyId, ingredientId)
                || modifierRepository.existsDeltaForIngredient(companyId, ingredientId);
    }
}
