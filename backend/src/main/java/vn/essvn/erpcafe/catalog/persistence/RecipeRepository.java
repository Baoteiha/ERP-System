package vn.essvn.erpcafe.catalog.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import vn.essvn.erpcafe.catalog.domain.Recipe;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    Optional<Recipe> findByProductId(UUID productId);
}
