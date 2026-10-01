-- Emails become case-insensitive: stored lower-case, unique on lower(email).
--
-- Existing rows that differ only by case would collide once lower-cased, and an unattended
-- deploy must not fail on them. Rule: within each such group the oldest account (by created_at,
-- then id) keeps the plain lower-cased email; every later one gets "+dup<n>" appended to its
-- local part (n = 1, 2, ... in that order), so nothing is deleted and each stays addressable.
-- An administrator can rename the suffixed accounts afterwards.
-- dropped first: lower-casing one row can transiently collide with a not-yet-updated one
ALTER TABLE app_user DROP CONSTRAINT uk_user_email;

WITH ranked AS (
    SELECT id,
           lower(email) AS lowered,
           row_number() OVER (PARTITION BY lower(email) ORDER BY created_at, id) AS rn
    FROM app_user
)
UPDATE app_user u
SET email = CASE
        WHEN r.rn = 1 THEN r.lowered
        ELSE regexp_replace(r.lowered, '^([^@]*)', '\1+dup' || (r.rn - 1))
    END
FROM ranked r
WHERE u.id = r.id AND u.email <> CASE
        WHEN r.rn = 1 THEN r.lowered
        ELSE regexp_replace(r.lowered, '^([^@]*)', '\1+dup' || (r.rn - 1))
    END;

CREATE UNIQUE INDEX uk_user_email_lower ON app_user (lower(email));

-- the daily purge scans by expiry
CREATE INDEX idx_refresh_expires_at ON refresh_token (expires_at);
