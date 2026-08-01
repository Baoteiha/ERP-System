package vn.essvn.erpcafe.sales.application;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.sales.api.DailySales;
import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.sales.api.SalesSummary;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;

/** Implements the published {@link SalesApi} revenue aggregates. */
@Service
@Transactional(readOnly = true)
public class SalesReportService implements SalesApi {

    private final OrderRepository orderRepository;

    public SalesReportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public SalesSummary summary(UUID branchId, Instant from, Instant to) {
        return orderRepository.summarize(branchId, from, to);
    }

    @Override
    public List<DailySales> salesByDay(UUID branchId, Instant from, Instant to) {
        return orderRepository.revenueByDay(branchId, from, to).stream()
                .map(row -> new DailySales(
                        ((Date) row[0]).toLocalDate(),
                        ((Number) row[1]).longValue(),
                        (BigDecimal) row[2]))
                .toList();
    }
}
