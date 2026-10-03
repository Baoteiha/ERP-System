# Stock Guard — Rulings

Decisions made while executing `2026-10-03-stock-guard.md` on branch `feat/stock-guard`. The owner
decided the big questions first: block the sale with no override, count untracked ingredients as 0,
show the problem on POS tiles and in the order panel, and enforce it on the server under the
existing stock-row lock. The rulings below settle what the plan or a review left open. Each line
reads *what was decided — why — what it costs if wrong*. S1 (model choice) is process-only and left
out.

- **S2:** accept rewriting InventoryFlowIntegrationTest.deductForOrder_allowsNegativeStock → deductForOrder_refusesToGoBelowZero — it pinned the warn-but-allow rule the owner reversed; same setup, now expects INSUFFICIENT_STOCK and unchanged stock — cost if wrong: none
- **S3:** an ingredient is short only when the sale takes some of it (net quantity > 0); Task 2's shortfalls/unavailable must use the same condition — cost if wrong: none
- **S4:** BusinessRuleException(code, msg, props) instead of a named InsufficientStockException (brief over spec wording; same API contract); required/onHand serialize as JSON numbers (scale 4); zero-total orders that auto-complete are refused on create when short — consistent with "stock taken on completion"
- **S5:** an unknown/foreign ingredient makes that product unavailable on the menu (menu stays up) while the quote/payment keep throwing not-found — saves and the reference check make it unreachable in practice — cost if wrong: none
- **S6:** a tile shows "Out of stock" when the product can't be made as standard; it stays addable when the product has modifier groups (a substitution may fit) and is disabled only when it has none — the quote's shortfalls remain authoritative and still disable Pay — cost if wrong: a cashier may add an out-of-stock product that then shows a shortfall
- **S7:** Task 4 checked by the controller instead of a dispatched reviewer — docs-only, 19 lines, each statement matches Tasks 1–3 as reviewed — cost if wrong: final review catches it
- **S8:** one small final round fixes minors 1–6 (POS: refusal disables Pay + aria-describedby; submit re-checks shortfalls; refusal ignores a null request; reload menu on window focus/visibility; server detail gains the unit "51 pc left" — spec wording superseded; handler skips reserved property names; getOrDefault on ingredient names) — cost if wrong: none
