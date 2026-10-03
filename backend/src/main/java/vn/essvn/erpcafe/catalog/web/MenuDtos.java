package vn.essvn.erpcafe.catalog.web;

import java.util.List;
import java.util.UUID;

import vn.essvn.erpcafe.common.web.MoneyDto;

public final class MenuDtos {

    public record MenuModifier(UUID id, String name, MoneyDto priceDelta) {
    }

    public record MenuModifierGroup(UUID id, String name, int minSelect, int maxSelect, List<MenuModifier> modifiers) {
    }

    public record MenuProduct(
            UUID id,
            UUID categoryId,
            String name,
            String sku,
            MoneyDto effectivePrice,
            List<MenuModifierGroup> modifierGroups,
            boolean available) {
    }

    private MenuDtos() {
    }
}
