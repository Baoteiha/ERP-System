package vn.essvn.erpcafe.inventory.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.inventory.domain.Ingredient;

public interface IngredientRepository extends JpaRepository<Ingredient, UUID> {

    List<Ingredient> findByCompanyId(UUID companyId);

    boolean existsByCompanyIdAndName(UUID companyId, String name);
}
