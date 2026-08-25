# Microservices as the target, reached by staged extraction

The enterprise ERP's target runtime is a set of services — `iam-tenancy`,
`supply` (inventory + purchasing), `sales`, `staff`, `reporting`, and one
service per vertical (first: `fnb-catalog`) — behind an API gateway, with a
database per service. We get there by strangler extraction from the working
modular monolith, one service at a time, never by a big-bang split.

## Considered options

Remaining a modular monolith was the recommended default for a solo operator
(lowest operational surface; the `api/` facades already permit later
extraction). Microservices-now was chosen deliberately, accepting the
operational cost (gateway, broker, N deployables, distributed tracing) in
exchange for independent deploys and hard service isolation as the product
grows past one vertical.

## Consequences

- Extraction order follows inbound coupling, cheapest first: `reporting`
  (pure consumer) → `staff` → `supply` → the `sales`/`fnb-catalog` split last
  (the recipe-explosion/deduction seam is the hardest).
- `iam-tenancy` is extracted alongside the Zitadel migration, which already
  reshapes identity.
- Inventory and purchasing stay in one service: splitting them would turn
  goods receiving into a distributed transaction.
- Security hardening (tenancy scoping, RLS, IdP) completes in the monolith
  first — a leaky base must not be distributed.
- No distributed transactions anywhere; consumers are idempotent (the stock
  ledger's `(refType, refId)` guard is the template).
