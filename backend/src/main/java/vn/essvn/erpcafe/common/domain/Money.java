package vn.essvn.erpcafe.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Monetary value object: an exact {@link BigDecimal} amount plus a currency
 * code. Always use this (never {@code double}/{@code float}) for money.
 *
 * <p>Amounts are normalised to 4 decimal places with {@link RoundingMode#HALF_EVEN}
 * (banker's rounding) and stored as {@code NUMERIC(19,4)}. Default currency is VND.
 */
@Embeddable
public class Money {

    public static final String DEFAULT_CURRENCY = "VND";
    // All amounts are normalised to 4 decimal places so equality/arithmetic are predictable
    // and match the NUMERIC(19,4) DB column. HALF_EVEN (banker's rounding) avoids the upward
    // bias of HALF_UP accumulating across many small money operations.
    public static final int SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    @Column(name = "amount", precision = 19, scale = SCALE)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    protected Money() {
        // for JPA
    }

    private Money(BigDecimal amount, String currency) {
        this.amount = amount == null ? null : amount.setScale(SCALE, ROUNDING);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount) {
        return of(amount, DEFAULT_CURRENCY);
    }

    public static Money zero() {
        return of(BigDecimal.ZERO, DEFAULT_CURRENCY);
    }

    public Money add(Money other) {
        // Adding across currencies is a bug, not something to silently coerce — fail loudly.
        requireSameCurrency(other);
        return of(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return of(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return of(amount.multiply(factor), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!Objects.equals(currency, other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        return Objects.equals(amount, money.amount) && Objects.equals(currency, money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return amount + " " + currency;
    }
}
