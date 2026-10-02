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

    /** Whether the version in force of any live product's recipe in the company has a line for the ingredient. */
    @Query("select count(r) > 0 from Recipe r join r.versions v join v.lines l, Product p "
            + "where p.id = r.productId and p.companyId = :companyId and v.versionNo = r.currentVersionNo "
            + "and l.ingredientId = :ingredientId")
    boolean existsLineForIngredient(@Param("companyId") UUID companyId, @Param("ingredientId") UUID ingredientId);
}
