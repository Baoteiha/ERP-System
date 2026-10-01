-- subtotal used to be net of line discounts, which counted them once in discount_total and
-- once again in the subtotal. It is now gross; grand totals were always right and stay as is.
UPDATE sales_order o
SET subtotal = o.subtotal + COALESCE(
        (SELECT SUM(l.line_discount) FROM order_line l WHERE l.order_id = o.id), 0);
