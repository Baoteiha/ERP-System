# Spring Modulith modules and domain events as the extraction seam

Inside the monolith (and later inside each service), modules are Spring
Modulith modules: boundaries verified by test, each module integration-testable
alone. Cross-module communication is synchronous facade calls for queries and
domain events for facts (`OrderCompleted`, `StockReceived`, …) published
through Modulith's transactional event registry — an outbox that delivers
in-process today and externalizes to RabbitMQ unchanged when a module is
extracted into a service (ADR-0002).

## Considered options

ArchUnit rules alone enforce boundaries but provide no event infrastructure,
so the audit trail and every extraction would be hand-built. Gradle
multi-module gives compile-time walls at the cost of a full build restructure.
Modulith matches the existing package-per-context + `api/` facade layout with
the least disruption and makes each extraction a transport change on proven
seams rather than a rewrite.

## Consequences

- Facts that other modules react to must be events, not direct calls — the
  sales → inventory deduction is the first migration (today a synchronous call
  returning COGS; becomes `OrderCompleted` consumed idempotently by supply).
- The event log doubles as the domain audit trail's source.
- Events are versioned contracts once externalized; changing one is a
  cross-service change and is treated with the same care as a REST contract.
- RabbitMQ is the broker (lighter to operate than Kafka at this scale);
  revisit only if event volume demands partitioned logs.
