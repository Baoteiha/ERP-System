package vn.essvn.erpcafe.inventory.api;

import vn.essvn.erpcafe.common.domain.Money;

/**
 * The cost of goods sold for a deduction: the total cost of the ingredients
 * consumed, valued at their moving-average unit cost at the time of deduction.
 */
public record CogsResult(Money totalCost) {
}
