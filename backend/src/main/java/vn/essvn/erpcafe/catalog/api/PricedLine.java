package vn.essvn.erpcafe.catalog.api;

import java.util.List;
import java.util.UUID;

import vn.essvn.erpcafe.common.domain.Money;

/**
 * A fully priced line for one product + selected modifiers, with the snapshot
 * detail the sales module records on an order (names + per-modifier deltas), so
 * a later menu/price change never rewrites order history.
 */
public record PricedLine(
        UUID productId,
        String productName,
        Money unitBasePrice,
        List<PricedModifier> modifiers,
        Money unitTotal) {

    /** One selected modifier, snapshotted with its name and price delta. */
    public record PricedModifier(UUID modifierId, String name, Money priceDelta) {
    }
}
