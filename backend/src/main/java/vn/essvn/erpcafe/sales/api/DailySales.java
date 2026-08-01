package vn.essvn.erpcafe.sales.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's revenue at a branch (day boundaries in UTC). */
public record DailySales(LocalDate day, long orderCount, BigDecimal revenue) {
}
