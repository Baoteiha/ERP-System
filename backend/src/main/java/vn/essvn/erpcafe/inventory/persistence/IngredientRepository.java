package vn.essvn.erpcafe.inventory.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.Ingredient;

/** Ingredients are tenant-owned: every query here sees only the current company's (see {@code TenantOwnedEntity}). */
public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

    boolean existsByName(String name);
}
