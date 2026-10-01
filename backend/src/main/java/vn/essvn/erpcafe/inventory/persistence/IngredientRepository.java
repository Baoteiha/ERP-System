package vn.essvn.erpcafe.inventory.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.inventory.domain.Ingredient;

/** Ingredients are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
@Transactional(readOnly = true)
public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

    boolean existsByName(String name);
}
