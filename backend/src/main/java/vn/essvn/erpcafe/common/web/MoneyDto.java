package vn.essvn.erpcafe.common.web;

import java.math.BigDecimal;

import vn.essvn.erpcafe.common.domain.Money;

/** Wire representation of {@link Money}. */
public record MoneyDto(BigDecimal amount, String currency) {

    public static MoneyDto from(Money money) {
        return money == null ? null : new MoneyDto(money.getAmount(), money.getCurrency());
    }
}
