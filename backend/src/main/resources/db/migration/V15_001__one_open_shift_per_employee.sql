-- One open (IN_PROGRESS) shift per employee, across every branch: the partial unique index is the
-- real guard against two simultaneous clock-ins; the service check only gives the friendly 422.
-- Employees are no longer unique by full name (two people can share one), and V6 never added a
-- constraint for it, so nothing is dropped here.

-- Legacy data: if an employee already has several open shifts, keep the latest and cancel the
-- older ones so the index can be built.
UPDATE shift
SET status = 'CANCELLED'
WHERE status = 'IN_PROGRESS'
  AND id NOT IN (
      SELECT DISTINCT ON (employee_id) id
      FROM shift
      WHERE status = 'IN_PROGRESS'
      ORDER BY employee_id, clock_in_at DESC NULLS LAST, id
  );

CREATE UNIQUE INDEX uk_shift_one_open_per_employee ON shift (employee_id) WHERE status = 'IN_PROGRESS';
