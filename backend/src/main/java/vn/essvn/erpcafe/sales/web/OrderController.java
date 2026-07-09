package vn.essvn.erpcafe.sales.web;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import vn.essvn.erpcafe.common.web.ApiVersions;
import vn.essvn.erpcafe.common.web.MoneyDto;
import vn.essvn.erpcafe.common.web.PageResponse;
import vn.essvn.erpcafe.sales.application.OrderService;
import vn.essvn.erpcafe.sales.application.SalesInputs.CreateOrderInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.LineInput;
import vn.essvn.erpcafe.sales.application.SalesInputs.PaymentInput;
import vn.essvn.erpcafe.sales.domain.Order;
import vn.essvn.erpcafe.sales.domain.OrderLine;
import vn.essvn.erpcafe.sales.domain.Payment;
import vn.essvn.erpcafe.sales.domain.PaymentMethod;
import vn.essvn.erpcafe.sales.web.OrderDtos.CreateOrderRequest;
import vn.essvn.erpcafe.sales.web.OrderDtos.LineResponse;
import vn.essvn.erpcafe.sales.web.OrderDtos.ModifierResponse;
import vn.essvn.erpcafe.sales.web.OrderDtos.OrderResponse;
import vn.essvn.erpcafe.sales.web.OrderDtos.PaymentRequest;
import vn.essvn.erpcafe.sales.web.OrderDtos.PaymentResponse;
import vn.essvn.erpcafe.sales.web.OrderDtos.RefundRequest;

/**
 * POS orders for the active branch (requires {@code X-Branch-Id}):
 * create → take payment(s) → complete, plus cancel / void / refund.
 */
@RestController
@RequestMapping(ApiVersions.V1 + "/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('sales:read')")
    public PageResponse<OrderResponse> list(Pageable pageable) {
        return PageResponse.from(orderService.list(pageable).map(OrderController::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:read')")
    public OrderResponse get(@PathVariable UUID id) {
        return toResponse(orderService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sales:write')")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(l.productId(), l.quantity(),
                        l.modifierIds() == null ? Set.of() : l.modifierIds(), l.lineDiscount()))
                .toList();
        OrderResponse body = toResponse(orderService.create(
                new CreateOrderInput(request.orderType(), request.taxRate(), request.orderDiscount(), lines)));
        return ResponseEntity.created(URI.create(ApiVersions.V1 + "/orders/" + body.id())).body(body);
    }

    @PostMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('sales:write')")
    public OrderResponse addPayment(@PathVariable UUID id, @Valid @RequestBody PaymentRequest request) {
        return toResponse(orderService.addPayment(id, new PaymentInput(request.method(), request.amount())));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('sales:write')")
    public OrderResponse complete(@PathVariable UUID id) {
        return toResponse(orderService.complete(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('sales:write')")
    public OrderResponse cancel(@PathVariable UUID id) {
        return toResponse(orderService.cancel(id));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('sales:refund')")
    public OrderResponse voidOrder(@PathVariable UUID id, @RequestBody(required = false) RefundRequest request) {
        return toResponse(orderService.voidOrder(id, refundMethod(request)));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('sales:refund')")
    public OrderResponse refund(@PathVariable UUID id, @RequestBody(required = false) RefundRequest request) {
        return toResponse(orderService.refund(id, refundMethod(request)));
    }

    private static PaymentMethod refundMethod(RefundRequest request) {
        return request != null && request.method() != null ? request.method() : PaymentMethod.CASH;
    }

    // --- mapping ---

    private static OrderResponse toResponse(Order o) {
        return new OrderResponse(o.getId(), o.getBranchId(), o.getCashierUserId(),
                o.getOrderType().name(), o.getStatus().name(), o.getCurrency(), o.getTaxRate(),
                o.getSubtotal(), o.getDiscountTotal(), o.getTaxTotal(), o.getGrandTotal(), o.getCogsTotal(),
                o.amountPaid().getAmount(),
                o.getLines().stream().map(OrderController::toLine).toList(),
                o.getPayments().stream().map(OrderController::toPayment).toList(),
                o.getCreatedAt());
    }

    private static LineResponse toLine(OrderLine l) {
        List<ModifierResponse> mods = l.getModifiers().stream()
                .map(m -> new ModifierResponse(m.getModifierId(), m.getModifierName(), MoneyDto.from(m.getPriceDelta())))
                .toList();
        return new LineResponse(l.getId(), l.getProductId(), l.getProductName(),
                MoneyDto.from(l.getUnitPrice()), l.getQuantity(), MoneyDto.from(l.getLineDiscount()),
                MoneyDto.from(l.getLineTotal()), mods);
    }

    private static PaymentResponse toPayment(Payment p) {
        return new PaymentResponse(p.getMethod().name(), p.getType().name(),
                MoneyDto.from(p.getAmount()), p.getCreatedAt());
    }
}
