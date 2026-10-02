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
        order.addLine(priced("25000"), 2, new BigDecimal("5000"), null);
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
        order.addLine(priced("33333"), 1, BigDecimal.ZERO, null);

        assertThat(order.getTaxTotal()).isEqualTo(new BigDecimal("2666.6400"));
        assertThat(order.getGrandTotal()).isEqualTo(new BigDecimal("35999.6400"));
        assertThat(order.getGrandTotal()).isEqualTo(
                order.getSubtotal().subtract(order.getDiscountTotal()).add(order.getTaxTotal()));
    }

    @Test
    void tieOnTheMoneyScale_roundsHalfEven() {
        Order down = order("0.1");
        down.addLine(priced("0.0005"), 1, BigDecimal.ZERO, null); // tax 0.00005 is a tie: 0 is even, HALF_UP would give 0.0001
        assertThat(down.getTaxTotal()).isEqualTo(new BigDecimal("0.0000"));

        Order up = order("0.1");
        up.addLine(priced("0.0015"), 1, BigDecimal.ZERO, null); // tax 0.00015 is a tie: 2 is even, HALF_DOWN would give 0.0001
        assertThat(up.getTaxTotal()).isEqualTo(new BigDecimal("0.0002"));
    }

    @Test
    void lineDiscountAboveLineGross_isRejected() {
        Order order = order("0");
        assertThatThrownBy(() -> order.addLine(priced("25000"), 1, new BigDecimal("25001"), null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> order.addLine(priced("25000"), 1, new BigDecimal("-1"), null))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(order.getLines()).isEmpty();
    }

    @Test
    void orderDiscountAboveRemainingSubtotal_isRejected() {
        Order order = order("0");
        order.addLine(priced("25000"), 2, new BigDecimal("5000"), null);

        assertThatThrownBy(() -> order.applyOrderDiscount(new BigDecimal("45001")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> order.applyOrderDiscount(new BigDecimal("-1")))
                .isInstanceOf(BusinessRuleException.class);
        order.applyOrderDiscount(new BigDecimal("45000"));
        assertThat(order.getGrandTotal()).isEqualByComparingTo("0");
    }

    @Test
    void nonPositiveQuantity_isRejected() {
        assertThatThrownBy(() -> order("0").addLine(priced("1000"), 0, BigDecimal.ZERO, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void collectionsAreReadOnlyViews() {
        Order order = order("0");
        order.addLine(priced("1000"), 1, BigDecimal.ZERO, null);

        assertThatThrownBy(() -> order.getLines().add(order.getLines().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> order.getPayments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
