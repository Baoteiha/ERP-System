package vn.essvn.erpcafe.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: ReceiptSplitTest
 * Purpose: Pins how one delivered line splits into what the order counts, stock paid and free, and loss.
 */
class ReceiptSplitTest {

    private static final BigDecimal BOX = new BigDecimal("100");

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static void expect(ReceiptSplit s, String counted, String grow, String over, String charged,
            String free, String loss) {
        assertThat(s.countedQty()).isEqualByComparingTo(counted);
        assertThat(s.growOrderedBy()).isEqualByComparingTo(grow);
        assertThat(s.overDeliveredQty()).isEqualByComparingTo(over);
        assertThat(s.chargedIntoStock()).isEqualByComparingTo(charged);
        assertThat(s.freeIntoStock()).isEqualByComparingTo(free);
        assertThat(s.lossQty()).isEqualByComparingTo(loss);
    }

    @Test
    void fullDelivery_entersEveryUnitAtCost() {
        expect(ReceiptSplit.of(n("5"), n("5"), BOX, null, n("0")), "5", "0", "0", "500", "0", "0");
    }

    @Test
    void damagedUnits_countTowardTheOrder_butNotStock() {
        expect(ReceiptSplit.of(n("5"), n("5"), BOX, null, n("40")), "5", "0", "0", "460", "0", "40");
    }

    @Test
    void shortDelivery_countsWhatArrived() {
        expect(ReceiptSplit.of(n("4"), n("5"), BOX, null, n("0")), "4", "0", "0", "400", "0", "0");
    }

    @Test
    void chargedExtra_growsTheOrder() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.CHARGED, n("0")), "6", "1", "1", "600", "0", "0");
    }

    @Test
    void freeExtra_entersFree_andTheOrderStays() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("0")), "5", "0", "1", "500", "100", "0");
    }

    @Test
    void damage_countsAgainstThePaidUnitsFirst() {
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("150")), "5", "0", "1", "350", "100", "150");
        expect(ReceiptSplit.of(n("6"), n("5"), BOX, OverDeliveryKind.FREE, n("550")), "5", "0", "1", "0", "50", "500");
    }

    @Test
    void wholeBoxRuined_entersNothing_butCountsTheBox() {
        expect(ReceiptSplit.of(n("1"), n("1"), BOX, null, n("100")), "1", "0", "0", "0", "0", "100");
    }

    @Test
    void lineWithoutAPack_countsInItsOwnUnit() {
        expect(ReceiptSplit.of(n("2.5"), n("10"), BigDecimal.ONE, null, n("0.5")), "2.5", "0", "0", "2.0", "0", "0.5");
    }

    @Test
    void extraWithoutAChoice_orAChoiceWithoutExtra_isRefused() {
        assertThatThrownBy(() -> ReceiptSplit.of(n("6"), n("5"), BOX, null, n("0")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("choose charged or free gift");
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, OverDeliveryKind.FREE, n("0")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Nothing arrived beyond");
    }

    @Test
    void damageBeyondTheDelivery_orNegative_isRefused() {
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, null, n("501")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cannot exceed what was delivered");
        assertThatThrownBy(() -> ReceiptSplit.of(n("5"), n("5"), BOX, null, n("-1")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
