package vn.essvn.erpcafe.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.domain.ModifierRecipeLine;
import vn.essvn.erpcafe.catalog.persistence.ModifierGroupRepository;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.ConflictException;
import vn.essvn.erpcafe.common.exception.ResourceNotFoundException;
import vn.essvn.erpcafe.identity.security.CurrentUser;

/** Modifier group + modifier management within the acting user's company. */
@Service
@Transactional
public class ModifierService {

    private final ModifierGroupRepository groupRepository;
    private final CurrentUser currentUser;

    public ModifierService(ModifierGroupRepository groupRepository, CurrentUser currentUser) {
        this.groupRepository = groupRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ModifierGroup> listGroups() {
        return groupRepository.findByCompanyId(currentUser.require().companyId());
    }

    @Transactional(readOnly = true)
    public ModifierGroup getGroup(UUID id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ModifierGroup", id));
    }

    public ModifierGroup createGroup(String name, int minSelect, int maxSelect) {
        UUID companyId = currentUser.require().companyId();
        if (groupRepository.existsByCompanyIdAndName(companyId, name)) {
            throw new ConflictException("Modifier group already exists: " + name);
        }
        return groupRepository.save(new ModifierGroup(companyId, name, minSelect, maxSelect));
    }

    public void deleteGroup(UUID id) {
        groupRepository.delete(getGroup(id));
    }

    /** Adds a modifier (with optional ingredient-consumption deltas) to a group. */
    public Modifier addModifier(UUID groupId, String name, BigDecimal priceDelta, int displayOrder,
            List<ConsumptionLine> recipeDeltas) {
        ModifierGroup group = getGroup(groupId);
        Modifier modifier = new Modifier(name, Money.of(priceDelta), displayOrder);
        if (recipeDeltas != null) {
            for (ConsumptionLine line : recipeDeltas) {
                modifier.getRecipeLines().add(
                        new ModifierRecipeLine(line.ingredientId(), line.quantity(), line.unit()));
            }
        }
        group.getModifiers().add(modifier);
        // cascade persists the new modifier when the group flushes
        return modifier;
    }
}
