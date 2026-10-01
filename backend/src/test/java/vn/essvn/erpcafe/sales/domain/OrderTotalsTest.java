package vn.essvn.erpcafe.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.essvn.erpcafe.catalog.api.PricedLine;
import vn.essvn.erpcafe.common.domain.Money;
import vn.essvn.erpcafe.common.exception.BusinessRuleException;

/**
 * Class: OrderTotalsTest
 * Purpose: Proves the order computes gross, discounts, net, tax and grand total itself, rounded once and exactly.
 */
class OrderTotalsTest {

    private static PricedLine priced(String unit) {
        Money m = Money.of(new BigDecimal(unit));
        return new PricedLine(UUID.randomUUID(), "Latte", m, List.of(), m);
    }

    private static Order order(String taxRate) {
        return new Order(UUID.randomUUID(), null, OrderType.DINE_IN, new BigDecimal(taxRate));
    }

    @Test
    void subtotalIsGross_discountsCountOnce_taxOnNet() {
        Order order = order("0.1");
        order.addLine(priced("25000"), 2, new BigDecimal("5000"));
        order.applyOrderDiscount(new BigDecimal("3000"));

        assertThat(order.getSubtotal()).isEqualByComparingTo("50000");
        assertThat(order.getDiscountTotal()).isEqualByComparingTo("8000");
        assertThat(order.getTaxTotal()).isEqualByComparingTo("4200");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("46200");
        assertThat(order.getLines().get(0).getLineTotal().getAmount()).isEqualByComparingTo("45000");
    }

    @Test
    void fractionalDong_roundsHalfEvenToMoneyScale_andGrandIsNetPlusTax() {
        Order order = order("0.08");
        order.addLine(priced("33333"), 1, BigDecimal.ZERO);

        assertThat(order.getTaxTotal()).isEqualTo(new BigDecimal("2666.6400"));
        assertThat(order.getGrandTotal()).isEqualTo(new BigDecimal("35999.6400"));
        assertThat(order.getGrandTotal()).isEqualTo(
                order.getSubtotal().subtract(order.getDiscountTotal()).add(order.getTaxTotal()));
    }

    @Test
    void halfEvenAtTheScaleBoundary() {
        Order order = order("0.00005");
        order.addLine(priced("1.5"), 1, BigDecimal.ZERO); // 1.5 × 0.00005 = 0.000075 → 0.0001 (not a tie)
        order.applyOrderDiscount(BigDecimal.ZERO);
        assertThat(order.getTaxTotal()).isEqualTo(new BigDecimal("0.0001"));

        Order tie = order("0.00005");
        tie.addLine(priced("0.5"), 1, BigDecimal.ZERO); // 0.000025 → 0.0000
        assertThat(tie.getTaxTotal()).isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void lineDiscountAboveLineGross_isRejected() {
        Order order = order("0");
        assertThatThrownBy(() -> order.addLine(priced("25000"), 1, new BigDecimal("25001")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> order.addLine(priced("25000"), 1, new BigDecimal("-1")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(order.getLines()).isEmpty();
    }

    @Test
    void orderDiscountAboveRemainingSubtotal_isRejected() {
        Order order = order("0");
        order.addLine(priced("25000"), 2, new BigDecimal("5000"));

        assertThatThrownBy(() -> order.applyOrderDiscount(new BigDecimal("45001")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> order.applyOrderDiscount(new BigDecimal("-1")))
                .isInstanceOf(BusinessRuleException.class);
        order.applyOrderDiscount(new BigDecimal("45000"));
        assertThat(order.getGrandTotal()).isEqualByComparingTo("0");
    }

    @Test
    void nonPositiveQuantity_isRejected() {
        assertThatThrownBy(() -> order("0").addLine(priced("1000"), 0, BigDecimal.ZERO))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void collectionsAreReadOnlyViews() {
        Order order = order("0");
        order.addLine(priced("1000"), 1, BigDecimal.ZERO);

        assertThatThrownBy(() -> order.getLines().add(order.getLines().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> order.getPayments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
