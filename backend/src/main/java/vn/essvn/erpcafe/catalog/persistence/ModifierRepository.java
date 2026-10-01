package vn.essvn.erpcafe.catalog.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.essvn.erpcafe.catalog.domain.Modifier;

public interface ModifierRepository extends JpaRepository<Modifier, UUID> {

    /** Tenant-scoped lookup through the owning group: empty (→ 404) for another company's modifier. */
    @Query("select m from ModifierGroup g join g.modifiers m where m.id = :id and g.companyId = :companyId")
    Optional<Modifier> findByIdAndCompanyId(@Param("id") UUID id, @Param("companyId") UUID companyId);

    /** Whether any live modifier of the company has a recipe delta for the ingredient. */
    @Query("select count(l) > 0 from ModifierGroup g join g.modifiers m join m.recipeLines l "
            + "where g.companyId = :companyId and l.ingredientId = :ingredientId")
    boolean existsDeltaForIngredient(@Param("companyId") UUID companyId, @Param("ingredientId") UUID ingredientId);
}
