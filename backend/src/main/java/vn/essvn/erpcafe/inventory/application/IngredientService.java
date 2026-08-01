package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;
import vn.essvn.erpcafe.inventory.domain.Ingredient;
import vn.essvn.erpcafe.inventory.persistence.IngredientRepository;

/** Ingredient master-data management within the acting user's company. */
@Service
@Transactional
public class IngredientService {

    private final IngredientRepository ingredientRepository;
    private final CurrentUser currentUser;

    public IngredientService(IngredientRepository ingredientRepository, CurrentUser currentUser) {
        this.ingredientRepository = ingredientRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Ingredient> list() {
        return ingredientRepository.findByCompanyId(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public Ingredient get(UUID id) {
        // Scope to the caller's company: a bare findById would let one company read,
        // edit, or delete another's ingredient by id (IDOR). 404 hides existence.
        return ingredientRepository.findByIdAndCompanyId(id, currentUser.require().companyId())
                .orElseThrow(() -> ResourceNotFoundException.of("Ingredient", id));
    }

    public Ingredient create(String name, String baseUnit, String category) {
        UUID companyId = currentUser.require().companyId();
        if (ingredientRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Ingredient already exists: " + name);
        }
        return ingredientRepository.save(new Ingredient(companyId, name, baseUnit, category));
    }

    public Ingredient update(UUID id, String name, String baseUnit, String category, boolean active) {
        Ingredient ingredient = get(id);
        if (!ingredient.getName().equals(name)
                && ingredientRepository.existsByCompanyIdAndName(ingredient.getCompanyId(), name)) {
            throw new ConflictException("Ingredient already exists: " + name);
        }
        ingredient.setName(name);
        ingredient.setBaseUnit(baseUnit);
        ingredient.setCategory(category);
        ingredient.setActive(active);
        return ingredient;
    }

    public void delete(UUID id) {
        ingredientRepository.delete(get(id));
    }
}
