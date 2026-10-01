-- A till retries a create it never saw the answer to; the client's request id makes that a no-op.
-- Existing orders and clients that send no id keep a NULL, which the partial index ignores.
ALTER TABLE sales_order ADD COLUMN client_request_id UUID;

CREATE UNIQUE INDEX uk_sales_order_branch_client_request
    ON sales_order (branch_id, client_request_id)
    WHERE client_request_id IS NOT NULL;
