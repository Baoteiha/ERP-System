package vn.essvn.erpcafe.catalog.web;

import java.util.Comparator;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.essvn.erpcafe.catalog.application.MenuService;
import vn.essvn.erpcafe.catalog.application.MenuService.MenuEntry;
import vn.essvn.erpcafe.catalog.domain.Modifier;
import vn.essvn.erpcafe.catalog.domain.ModifierGroup;
import vn.essvn.erpcafe.catalog.web.MenuDtos.MenuModifier;
import vn.essvn.erpcafe.catalog.web.MenuDtos.MenuModifierGroup;
import vn.essvn.erpcafe.catalog.web.MenuDtos.MenuProduct;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;

/**
 * Class: MenuController
 * Purpose: Serves the till its menu: what the active branch sells, at the price it charges there.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/menu")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('catalog:read')")
    public List<MenuProduct> menu() {
        return menuService.menu().stream().map(MenuController::toProduct).toList();
    }

    private static MenuProduct toProduct(MenuEntry entry) {
        return new MenuProduct(entry.product().getId(), entry.product().getCategoryId(), entry.product().getName(),
                entry.product().getSku(), MoneyDto.from(entry.effectivePrice()),
                entry.product().getModifierGroups().stream()
                        .sorted(Comparator.comparing(ModifierGroup::getName))
                        .map(MenuController::toGroup).toList());
    }

    private static MenuModifierGroup toGroup(ModifierGroup g) {
        return new MenuModifierGroup(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                g.getModifiers().stream().filter(Modifier::isActive)
                        .map(m -> new MenuModifier(m.getId(), m.getName(), MoneyDto.from(m.getPriceDelta()))).toList());
    }
}
