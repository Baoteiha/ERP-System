-- The failed-login throttle's counters (AUTH-G18), in the database so every instance counts the same
-- failures and a restart forgets none. One row per counter: key is the SHA-256 (hex) of the counter's
-- name — an email and client address, or an address alone — so the table holds neither emails nor
-- addresses, and a row's size is fixed whatever was typed. count failures have been seen since
-- window_start; a failure after the window lapses starts a new one. Rows whose window has lapsed are
-- purged by the app (LoginAttemptService).
--
-- Owner-only: logins run before a company is known, so only the schema owner (SystemTransactions)
-- reads and writes it. erp_app holds no privilege on it, and row-level security with no policy would
-- refuse it every row even if one were granted.
CREATE TABLE login_attempt (
    key          VARCHAR(64)  NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    count        INT          NOT NULL,
    CONSTRAINT pk_login_attempt PRIMARY KEY (key)
);

CREATE INDEX idx_login_attempt_window_start ON login_attempt (window_start);

REVOKE ALL ON login_attempt FROM erp_app;
ALTER TABLE login_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE login_attempt FORCE ROW LEVEL SECURITY;
