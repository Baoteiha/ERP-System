package vn.essvn.erpcafe.catalog.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.RecipeVersion;

/**
 * Class: RecipeVersionRepository
 * Purpose: Reads recipe versions, which are only ever written through their recipe.
 */
@Transactional(readOnly = true)
public interface RecipeVersionRepository extends Repository<RecipeVersion, UUID> {

    @Query("select v from Recipe r join r.versions v where r.productId = :productId and v.versionNo = r.currentVersionNo")
    Optional<RecipeVersion> findCurrent(@Param("productId") UUID productId);

    /** The version in force of a live product of the company. */
    @Query("select v.id from Product p, Recipe r join r.versions v where r.productId = p.id"
            + " and p.id = :productId and p.companyId = :companyId and v.versionNo = r.currentVersionNo")
    Optional<UUID> findCurrentId(@Param("companyId") UUID companyId, @Param("productId") UUID productId);

    /** Every line of the version in force for each of the company's live products among the ids, in one read. */
    @Query("""
            select new vn.essvn.erpcafe.catalog.persistence.CurrentRecipeLine(r.productId, l.ingredientId, l.quantity, l.unit)
            from Product p, Recipe r join r.versions v join v.lines l
            where r.productId = p.id and p.companyId = :companyId and p.id in :productIds
              and v.versionNo = r.currentVersionNo
            """)
    List<CurrentRecipeLine> findCurrentLines(@Param("companyId") UUID companyId,
            @Param("productIds") Collection<UUID> productIds);

    /**
     * A version of the company's product's recipe, whether or not the product has since been deleted:
     * a line taken before the delete still consumes it (native, so the product's deleted filter does not apply).
     */
    @Query(value = "SELECT v.* FROM recipe_version v JOIN recipe r ON r.id = v.recipe_id"
            + " JOIN product p ON p.id = r.product_id"
            + " WHERE v.id = :versionId AND r.product_id = :productId AND p.company_id = :companyId",
            nativeQuery = true)
    Optional<RecipeVersion> findOfProduct(@Param("companyId") UUID companyId, @Param("productId") UUID productId,
            @Param("versionId") UUID versionId);
}
