package vn.essvn.erpcafe.inventory.api;

import java.util.UUID;

/**
 * Class: IngredientReferenceCheck
 * Purpose: Lets another module say it still uses an ingredient, so inventory can refuse to delete it without importing that module.
 */
public interface IngredientReferenceCheck {

    boolean isReferenced(UUID companyId, UUID ingredientId);
}
