package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's net sales at a branch (net of discounts, tax excluded; the day is a calendar date in the reporting zone). */
public record DailySales(LocalDate day, long orderCount, BigDecimal revenue) {
}
