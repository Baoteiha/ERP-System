-- One open (IN_PROGRESS) shift per employee, across every branch: the partial unique index is the
-- real guard against two simultaneous clock-ins; the service check only gives the friendly 422.
-- Employees are no longer unique by full name (two people can share one), and V6 never added a
-- constraint for it, so nothing is dropped here.
--
-- Legacy data: if an employee already has several open shifts, the latest stays open. Each older
-- one is closed as COMPLETED at LEAST(the employee's next clock-in, its own clock-in + 16 h), with
-- labor_cost = hours x the shift's snapshot hourly_rate rounded HALF_EVEN to scale 4 (PostgreSQL
-- ROUND on numeric rounds half away from zero, so the tie case is handled explicitly). Hours worked
-- are therefore kept in labor cost, capped at 16 h per forgotten shift; a manager can adjust them later.

WITH ordered AS (
    SELECT id, employee_id, clock_in_at, hourly_rate, hourly_rate_currency,
           LEAD(clock_in_at) OVER (PARTITION BY employee_id ORDER BY clock_in_at, id) AS next_in
    FROM shift
    WHERE status = 'IN_PROGRESS'
),
closing AS (
    SELECT id, clock_in_at,
           LEAST(next_in, clock_in_at + INTERVAL '16 hours') AS out_at,
           COALESCE(hourly_rate, 0) AS rate,
           COALESCE(hourly_rate_currency, 'VND') AS currency,
           EXTRACT(EPOCH FROM LEAST(next_in, clock_in_at + INTERVAL '16 hours') - clock_in_at)::numeric AS secs
    FROM ordered
    WHERE next_in IS NOT NULL
),
costed AS (
    SELECT id, out_at, currency,
           COALESCE(rate, 0) * secs / 3600 AS exact
    FROM closing
)
UPDATE shift s
SET status = 'COMPLETED',
    clock_out_at = c.out_at,
    labor_cost = CASE
        WHEN abs(c.exact * 10000 - trunc(c.exact * 10000)) = 0.5
            THEN (trunc(c.exact * 10000) + (trunc(c.exact * 10000)::numeric % 2)
                  * sign(c.exact)) / 10000
        ELSE round(c.exact, 4)
    END,
    labor_cost_currency = c.currency
FROM costed c
WHERE s.id = c.id;

CREATE UNIQUE INDEX uk_shift_one_open_per_employee ON shift (employee_id) WHERE status = 'IN_PROGRESS';
