package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

    List<Ingredient> findByCompanyId(UUID companyId);

    /** Tenant-scoped lookup: returns empty (→ 404) for another company's id. */
    Optional<Ingredient> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
