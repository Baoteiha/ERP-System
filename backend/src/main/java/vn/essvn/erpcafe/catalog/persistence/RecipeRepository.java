package vn.essvn.erpcafe.catalog.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Recipe;

@Transactional(readOnly = true)
public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    Optional<Recipe> findByProductId(UUID productId);

    /** Whether any live product of the company has a recipe line for the ingredient. */
    @Query("select count(l) > 0 from Recipe r join r.lines l, Product p "
            + "where p.id = r.productId and p.companyId = :companyId and l.ingredientId = :ingredientId")
    boolean existsLineForIngredient(@Param("companyId") UUID companyId, @Param("ingredientId") UUID ingredientId);
}
