package vn.essvn.erpcafe.catalog.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Modifier;

@Transactional(readOnly = true)
public interface ModifierRepository extends JpaRepository<Modifier, UUID> {

    /**
     * The company's live modifiers among the ids; any other id is left out. A modifier has no company of
     * its own: it is found through its group, which the tenant filter scopes as well.
     */
    @Query("select m from ModifierGroup g join g.modifiers m where m.id in :ids and g.companyId = :companyId")
    List<Modifier> findInCompany(@Param("companyId") UUID companyId, @Param("ids") Collection<UUID> ids);

    /** Whether any live modifier of the company has a recipe delta for the ingredient. */
    @Query("select count(m) > 0 from ModifierGroup g join g.modifiers m join m.recipeLines l "
            + "where g.companyId = :companyId and l.ingredientId = :ingredientId")
    boolean existsDeltaForIngredient(@Param("companyId") UUID companyId, @Param("ingredientId") UUID ingredientId);
}
