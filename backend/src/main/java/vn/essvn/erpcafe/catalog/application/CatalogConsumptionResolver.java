package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.ConsumptionLine;
import vn.essvn.erpcafe.catalog.domain.RecipeVersion;
import vn.essvn.erpcafe.catalog.persistence.ModifierRepository;
import vn.essvn.erpcafe.catalog.persistence.RecipeVersionRepository;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.api.ConsumptionResolver;
import vn.essvn.erpcafe.inventory.api.StockLine;

/**
 * Class: CatalogConsumptionResolver
 * Purpose: Answers the core's consumption port from a product's recipe versions and its selected modifiers' deltas.
 */
@Component
@Transactional(readOnly = true)
public class CatalogConsumptionResolver implements ConsumptionResolver {

    private final RecipeVersionRepository versionRepository;
    private final ModifierRepository modifierRepository;

    public CatalogConsumptionResolver(RecipeVersionRepository versionRepository,
            ModifierRepository modifierRepository) {
        this.versionRepository = versionRepository;
        this.modifierRepository = modifierRepository;
    }

    @Override
    public Optional<UUID> currentRecipeVersion(UUID companyId, UUID productId) {
        return versionRepository.findCurrentId(companyId, productId);
    }

    @Override
    public List<StockLine> consumptionFor(UUID companyId, UUID productId, int quantity, Set<UUID> modifierIds,
            UUID recipeVersionId) {
        if (recipeVersionId == null) {
            return List.of();
        }
        RecipeVersion version = versionRepository.findOfProduct(companyId, productId, recipeVersionId)
                .orElseThrow(() -> ResourceNotFoundException.of("RecipeVersion", recipeVersionId));
        BigDecimal times = BigDecimal.valueOf(quantity);

        // Netted per (ingredient, unit). Units are not converted here (the inventory ledger converts to
        // the base unit once), so the same ingredient in two units stays two lines.
        Map<String, StockLine> net = new LinkedHashMap<>();
        version.getLines().forEach(line -> add(net, line, times));
        // Modifiers layer signed deltas on top (extra shot = +coffee; oat = -dairy, +oat). One no longer
        // in the company's live groups is skipped rather than failing the sale.
        if (modifierIds != null && !modifierIds.isEmpty()) {
            modifierRepository.findInCompany(companyId, modifierIds)
                    .forEach(modifier -> modifier.getRecipeLines().forEach(delta -> add(net, delta, times)));
        }
        return List.copyOf(net.values());
    }

    private static void add(Map<String, StockLine> net, ConsumptionLine line, BigDecimal times) {
        String unit = line.unit().symbol();
        net.merge(line.ingredientId() + "|" + unit,
                new StockLine(line.ingredientId(), line.quantity().multiply(times), unit),
                (sum, more) -> new StockLine(sum.ingredientId(), sum.quantity().add(more.quantity()), unit));
    }
}
