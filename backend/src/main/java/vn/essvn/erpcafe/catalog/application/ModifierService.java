package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.ConsumptionLine;
import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.persistence.ModifierGroupRepository;
import vn.essvn.erpcafe.catalog.persistence.ProductRepository;
import vn.essvn.erpcafe.common.context.TenantContext;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.domain.Unit;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.inventory.api.InventoryApi;
import vn.essvn.erpcafe.inventory.api.StockLine;

/** Modifier group + modifier management within the acting user's company. */
@Service
@Transactional
public class ModifierService {

    private final ModifierGroupRepository groupRepository;
    private final ProductRepository productRepository;
    private final InventoryApi inventoryApi;

    public ModifierService(ModifierGroupRepository groupRepository, ProductRepository productRepository,
            InventoryApi inventoryApi) {
        this.productRepository = productRepository;
        this.inventoryApi = inventoryApi;
        this.groupRepository = groupRepository;
    }

    @Transactional(readOnly = true)
    public List<ModifierGroup> listGroups() {
        return groupRepository.findAll();
    }

    @Transactional(readOnly = true)
    public ModifierGroup getGroup(UUID id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ModifierGroup", id));
    }

    public ModifierGroup createGroup(String name, int minSelect, int maxSelect) {
        if (groupRepository.existsByName(name)) {
            throw new ConflictException("Modifier group already exists: " + name);
        }
        return groupRepository.save(new ModifierGroup(name, minSelect, maxSelect));
    }

    public ModifierGroup updateGroup(UUID id, String name, int minSelect, int maxSelect) {
        ModifierGroup group = getGroup(id);
        if (!group.getName().equals(name) && groupRepository.existsByName(name)) {
            throw new ConflictException("Modifier group already exists: " + name);
        }
        group.setName(name);
        group.setMinSelect(minSelect);
        group.setMaxSelect(maxSelect);
        return group;
    }

    public void deleteGroup(UUID id) {
        ModifierGroup group = getGroup(id);
        if (productRepository.existsByModifierGroupId(id)) {
            throw new ConflictException("Modifier group is attached to a product; detach it first");
        }
        groupRepository.delete(group);
    }

    /** Edits a modifier; {@code recipeDeltas == null} leaves its ingredient deltas untouched. */
    public Modifier updateModifier(UUID groupId, UUID modifierId, String name, BigDecimal priceDelta,
            boolean active, Integer displayOrder, List<ConsumptionInput> recipeDeltas) {
        Modifier modifier = requireModifier(getGroup(groupId), modifierId);
        if (recipeDeltas != null) {
            validateDeltas(recipeDeltas);
        }
        modifier.setName(name);
        modifier.setPriceDelta(Money.of(priceDelta));
        modifier.setActive(active);
        if (displayOrder != null) {
            modifier.setDisplayOrder(displayOrder);
        }
        if (recipeDeltas != null) {
            modifier.getRecipeLines().clear();
            addDeltas(modifier, recipeDeltas);
        }
        return modifier;
    }

    // Removed from the group, so the till no longer finds it among the product's offered
    // modifiers (priceLine answers 422); past order lines keep their own name/price snapshot.
    public void removeModifier(UUID groupId, UUID modifierId) {
        ModifierGroup group = getGroup(groupId);
        group.getModifiers().remove(requireModifier(group, modifierId));
    }

    private Modifier requireModifier(ModifierGroup group, UUID modifierId) {
        return group.getModifiers().stream().filter(m -> m.getId().equals(modifierId)).findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Modifier", modifierId));
    }

    private void validateDeltas(List<ConsumptionInput> recipeDeltas) {
        inventoryApi.validateConsumption(TenantContext.require(), recipeDeltas.stream()
                .map(l -> new StockLine(l.ingredientId(), l.quantity(), l.unit())).toList());
    }

    private static void addDeltas(Modifier modifier, List<ConsumptionInput> recipeDeltas) {
        for (ConsumptionInput line : recipeDeltas) {
            modifier.getRecipeLines().add(
                    new ConsumptionLine(line.ingredientId(), line.quantity(), Unit.parse(line.unit())));
        }
    }

    /** Adds a modifier (with optional ingredient-consumption deltas) to a group. */
    public Modifier addModifier(UUID groupId, String name, BigDecimal priceDelta, int displayOrder,
            List<ConsumptionInput> recipeDeltas) {
        ModifierGroup group = getGroup(groupId);
        if (recipeDeltas != null) {
            validateDeltas(recipeDeltas);
        }
        Modifier modifier = new Modifier(name, Money.of(priceDelta), displayOrder);
        if (recipeDeltas != null) {
            addDeltas(modifier, recipeDeltas);
        }
        group.getModifiers().add(modifier);
        // cascade persists the new modifier when the group flushes
        return modifier;
    }
}
