-- uk_user_email_lower becomes unique over live users only, as the application's own check
-- (UserRepository.existsByEmailInAnyCompany) already is and as V21 made every other soft-deletable
-- name and code: a soft-deleted user's email may go to a new account. No endpoint deletes a user yet,
-- so this keeps the index and the check from disagreeing once one does.
--
-- The old index covered every row, so live users cannot share an email. A database that lost the
-- index might have some anyway: the migration then stops and names them, since which account keeps
-- the address is a person's call.
DO $$
DECLARE
    n   BIGINT;
    ids TEXT;
BEGIN
    SELECT count(*) INTO n
      FROM app_user u
     WHERE u.deleted = FALSE
       AND EXISTS (SELECT 1 FROM app_user o
                    WHERE o.deleted = FALSE AND o.id <> u.id AND lower(o.email) = lower(u.email));
    IF n > 0 THEN
        SELECT string_agg(id::text, ', ' ORDER BY id) INTO ids
          FROM (SELECT u.id
                  FROM app_user u
                 WHERE u.deleted = FALSE
                   AND EXISTS (SELECT 1 FROM app_user o
                                WHERE o.deleted = FALSE AND o.id <> u.id AND lower(o.email) = lower(u.email))
                 ORDER BY u.id LIMIT 20) d;
        RAISE EXCEPTION 'V28_002: % live user(s) share an email with another live user (first 20 ids: %); resolve them by hand before migrating',
            n, ids;
    END IF;
END $$;

DROP INDEX IF EXISTS uk_user_email_lower;
CREATE UNIQUE INDEX uk_user_email_lower ON app_user (lower(email)) WHERE deleted = FALSE;
