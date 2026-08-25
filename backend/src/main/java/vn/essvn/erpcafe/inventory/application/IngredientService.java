package vn.essvn.erpcafe.inventory.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.common.domain.Unit;
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
        return ingredientRepository.save(new Ingredient(companyId, name, canonicalUnit(baseUnit), category));
    }

    public Ingredient update(UUID id, String name, String baseUnit, String category, boolean active,
            Long expectedVersion) {
        Ingredient ingredient = get(id);
        requireCurrentVersion(ingredient.getVersion(), expectedVersion);
        if (!ingredient.getName().equals(name)
                && ingredientRepository.existsByCompanyIdAndName(ingredient.getCompanyId(), name)) {
            throw new ConflictException("Ingredient already exists: " + name);
        }
        ingredient.setName(name);
        ingredient.setBaseUnit(canonicalUnit(baseUnit));
        ingredient.setCategory(category);
        ingredient.setActive(active);
        return ingredient;
    }

    // Base unit is the denominator of everything downstream — stock on hand, the
    // moving-average cost, every recipe line measured against it. Free text let "kg" and
    // "g" describe the same shelf, so it is resolved to a known Unit here and stored in
    // one canonical spelling; unrecognised text is rejected rather than guessed.
    private static String canonicalUnit(String baseUnit) {
        return Unit.parse(baseUnit).symbol();
    }

    // Stale-form guard: the client echoes the version it loaded. If the row has changed
    // since (version moved on), reject with 409 instead of silently overwriting the newer
    // edit. Enforced only when a version is supplied (older clients stay backward-compatible).
    private static void requireCurrentVersion(Long current, Long expected) {
        if (expected != null && !expected.equals(current)) {
            throw new ConflictException("Ingredient was modified by someone else — reload and try again");
        }
    }

    public void delete(UUID id) {
        ingredientRepository.delete(get(id));
    }
}
