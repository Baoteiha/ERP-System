package vn.essvn.erpcafe.sales.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.essvn.erpcafe.sales.api.SalesApi;
import vn.essvn.erpcafe.sales.domain.OrderStatus;
import vn.essvn.erpcafe.sales.persistence.OrderRepository;

/** Implements the published {@link SalesApi}. */
@Service
@Transactional(readOnly = true)
public class SalesReportService implements SalesApi {

    private final OrderRepository orderRepository;

    public SalesReportService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    public boolean hasOpenOrders(UUID branchId) {
        return orderRepository.existsByBranchIdAndStatus(branchId, OrderStatus.OPEN);
    }
}
