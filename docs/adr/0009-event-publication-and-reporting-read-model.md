# Domain events through a transactional outbox, and a reporting read model they feed

Modules announce facts as records in their `api` package — `sales.api.OrderCompleted`,
`sales.api.OrderReversed`, `staff.api.ShiftCompleted` — and reporting keeps its
own figures from them instead of asking sales, inventory and staff about every
period (REP-9). This amends ADR-0004: the events are the extraction seam as
planned, but they travel through an outbox of our own, not Spring Modulith's
event publication registry (see *Considered options*).

**Publishing.** `DomainEventPublisher.publish(event)` writes a `domain_event`
row (type, JSON payload, company) in the caller's transaction — it refuses to
run outside one (`MANDATORY`) or outside a company. The event therefore exists
exactly when the business change commits, and never when it rolls back.
Listeners do not run there.

- `OrderService` publishes `OrderCompleted` when an order completes, with its
  gross, discounts, net (gross − discounts), tax and the COGS the deduction
  recorded. A legacy PAID order completed late (`POST /orders/{id}/complete`)
  also names `previouslyBookedAt`, its creation time: reports had booked it
  then, and it moves to its completion day (V14).
- A void or refund publishes `OrderReversed` with the same figures and the
  cost the reversal returned to stock (zero for a legacy PAID order, which
  never took any).
- `ShiftService` publishes `ShiftCompleted` (clock-out, labour cost) on
  clock-out and on every adjustment: each states the shift's figures as they
  now are.

**Delivering.** `DomainEventDispatcher` runs every two seconds
(`app.events.dispatch-cron`; `-` turns it off, as the test profile does).
It reads the pending rows of every company as the system
(`SystemTransactions`, the owner connection), then delivers each in its own
transaction **inside the event's company** (`TenantContext.runAs`) on the app
connection, so a listener's writes are checked by `@TenantId` and row-level
security like a request's. It locks the row (`FOR UPDATE SKIP LOCKED`),
publishes `DomainEvent<T>` — the payload with the id and company it was
recorded under — to the `@EventListener`s, and sets `processed_at`; the
listeners' writes and the mark commit together.

- **Order.** A company's events are delivered in the order they were written
  (`seq`). One that fails holds back its company's later events until it is
  retried, and one another dispatcher is delivering holds them back for that
  run; other companies carry on.
- **Retries.** A failure records `attempts`, `last_error` and
  `next_attempt_at` (30 s × attempts). After ten attempts the event is given
  up: logged at ERROR, left unprocessed for someone to look at, and no longer
  holding its company back. Setting `attempts` back to 0 resubmits it.
- **Once.** Delivery is at least once; a consumer applies each event once by
  its id. The report projection records the id in `report_applied_event` in
  the same transaction as its figures.

**The read model.** `report_daily_branch` holds one row per branch and report
day — a calendar date in `app.reporting.zone` — with order count, gross,
discounts, net, tax, COGS and labour. `ReportProjection` adds to it: a sale on
its completion day (and takes it off `previouslyBookedAt`'s day), a reversal
as negatives on its own day with the order count unchanged, and a shift's
labour on its clock-out day. `report_shift_labor` keeps each shift's current
share, so an adjusted shift moves its labour instead of adding to it. All three
tables carry `company_id` under the usual policy. `ReportService` answers the
summary and the outlet comparison with one grouped query over the branches
asked about, and the daily series from the day rows; the grand total is net +
tax, and the margins are computed as before. The figures follow the rules of
Tasks 16 and 17.

**Whole days.** Figures are kept per day, so a report period is the set of
report days `[from, to)` touches; a `to` exactly at the start of a day leaves
that day out. The reports page and the dashboard already ask for whole days
in that zone.

**Backfill and rebuild.** `report_rebuild_branch(branch, zone)` (V23) recomputes
a branch's rows and shift shares from orders, the stock ledger and shifts under
the same rules — COGS as −quantity × unit cost of each sale depletion and
reversal, with the ledger's own sign (stock a sale returns is a negative
cost, as on the events), by movement time, rounded once a day, half to even,
at the Money scale — and marks
every event already written about the branch as applied, so one still on its
way is not counted twice. The migration runs it for every branch in the zone
of the `reportingZone` placeholder (application.yml passes
`app.reporting.zone`; a Flyway run outside the app must supply it). After
changing the zone, run it again for every branch as the schema owner, in a
`REPEATABLE READ` transaction so the sources and the events it marks are one
snapshot. A rebuild and the events can differ only immaterially: an event
carries its sale's cost rounded line by line (at most 0.00005 a line from one
rounding of the day), and a movement is stamped within milliseconds of its
sale's completion or reversal, which moves it to another day only within
those milliseconds of midnight. Tests that rewrite a row's timestamps after the fact — which no
event announces — call it too.

**Branch lifecycle events stay synchronous.** `BranchCreated`,
`BranchDeactivated` and `BranchDeleted` (Task 24) remain plain Spring events
handled inside the transaction that changed the branch: their consumer grants
and revokes access, which must change with the branch, not up to two seconds
later. The outbox is for facts whose consumers may lag — a read model today,
another service once one is extracted.

## Considered options

- **Spring Modulith's event publication registry.** Modulith 2.1.1 resolves
  against Spring Boot 4.1, but its registry does not fit tenancy enforced by
  the database. Its `event_publication` table has no company, so under forced
  row-level security it needs a policy that shows every company's payloads to
  the app role, or a company column filled from the session setting that the
  library knows nothing of. It marks a publication complete — and republishes
  incomplete ones — on its own executor threads, outside any company: on the
  app connection the policy hides the row, the completion silently updates
  nothing, and every event is republished on restart, unless tenant
  propagation and a system-scope republisher are bolted on. Its asynchronous
  delivery also leaves tests waiting rather than delivering. The outbox here
  is a few hundred lines on the existing tenant seam. On extraction, a broker
  replaces the dispatcher's transport; the event records, the envelope id and
  the idempotent consumers stay.
- **After-commit listeners without an outbox.** An event is lost if the
  process stops between the commit and the listener, and a failed listener is
  never retried.
- **Recomputing a day from the sources on each event.** Still asks every
  module about every period, which is what the read model removes.
- **Keeping reports exact to the instant.** Needs finer buckets than a day, or
  a live query for the partial days at each end of a period.

## Consequences

- Reports trail the sales and shifts behind them by up to one dispatch
  interval (two seconds by default).
- `domain_event` and `report_applied_event` grow by a row per event; nothing
  purges them yet.
- An event's type is stored as its class name. Moving or renaming a record
  strands its pending events (they fail and are given up), and a payload must
  stay readable by the record that reads it: add components, never rename
  them.
- COGS on the events is what the sale recorded (`Order.cogsTotal`, the cost
  the reversal returned), the ledger's cost with its own sign, and the rebuild
  costs the movements the same way. The old live query
  (`StockMovementRepository.costOfType`, no longer used by reports) took
  |quantity|, so where a modifier made an ingredient's net consumption
  negative, the read model now shows that stock as returned, where the old
  reports showed it as a cost.
- `SalesApi.summary`/`salesByDay`, `InventoryApi.depletionCost` and
  `StaffApi.laborCost`/`laborCostByBranch` no longer feed reports.
- New SQL outside the tenant seam, each naming its company or running in it,
  with row-level security as the backstop: the publisher's insert, the
  dispatcher's bookkeeping, `DailyBranchFiguresRepository.add` and
  `AppliedEventRepository.claim`. The dispatcher's read of pending events runs
  as the system and returns only ids and companies.
- Tests deliver events by calling `DomainEventDispatcher.dispatchPending()`.
