package vn.essvn.erpcafe.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void of_normalisesScaleToFourDecimals() {
        Money money = Money.of(new BigDecimal("10.5"), "VND");
        assertThat(money.getAmount()).isEqualByComparingTo("10.5000");
        assertThat(money.getAmount().scale()).isEqualTo(Money.SCALE);
        assertThat(money.getCurrency()).isEqualTo("VND");
    }

    @Test
    void of_appliesHalfEvenRounding() {
        // 2.00005 at scale 4 rounds half-even to 2.0000; 2.00015 -> 2.0002
        assertThat(Money.of(new BigDecimal("2.00005")).getAmount()).isEqualByComparingTo("2.0000");
        assertThat(Money.of(new BigDecimal("2.00015")).getAmount()).isEqualByComparingTo("2.0002");
    }

    @Test
    void zero_isZeroInDefaultCurrency() {
        Money zero = Money.zero();
        assertThat(zero.getAmount()).isEqualByComparingTo("0");
        assertThat(zero.getCurrency()).isEqualTo(Money.DEFAULT_CURRENCY);
    }

    @Test
    void add_sumsAmountsOfSameCurrency() {
        Money result = Money.of(new BigDecimal("10.00")).add(Money.of(new BigDecimal("5.50")));
        assertThat(result.getAmount()).isEqualByComparingTo("15.50");
    }

    @Test
    void add_rejectsDifferentCurrencies() {
        Money vnd = Money.of(BigDecimal.ONE, "VND");
        Money usd = Money.of(BigDecimal.ONE, "USD");
        assertThatThrownBy(() -> vnd.add(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void multiply_scalesAmount() {
        Money result = Money.of(new BigDecimal("3.00")).multiply(new BigDecimal("4"));
        assertThat(result.getAmount()).isEqualByComparingTo("12.00");
    }

    @Test
    void equals_comparesAmountAndCurrency() {
        assertThat(Money.of(new BigDecimal("1.0"))).isEqualTo(Money.of(new BigDecimal("1.0000")));
        assertThat(Money.of(BigDecimal.ONE, "VND")).isNotEqualTo(Money.of(BigDecimal.ONE, "USD"));
    }
}
