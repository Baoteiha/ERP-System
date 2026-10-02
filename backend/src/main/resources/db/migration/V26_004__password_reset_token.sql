-- Password reset by email (AUTH-G12). A reset request for an active account stores one token: only the
-- SHA-256 (hex) of the 32 random bytes mailed in the link, so the table yields nothing usable. A token
-- works once (used_at), until expires_at (30 minutes by default); confirming it sets the password, and
-- marks the user's other open tokens used too. Expired tokens are purged by the app.
--
-- Owner-only, like login_attempt: a reset runs signed out, before any company is known, so only the
-- schema owner (SystemTransactions) reads and writes it. erp_app holds no privilege on it, and row-level
-- security with no policy would refuse it every row even if one were granted.
CREATE TABLE password_reset_token (
    id         UUID         NOT NULL,
    user_id    UUID         NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- the open tokens of a user, which a request counts and a confirmation closes
CREATE INDEX idx_password_reset_token_user ON password_reset_token (user_id) WHERE used_at IS NULL;

REVOKE ALL ON password_reset_token FROM erp_app;
ALTER TABLE password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token FORCE ROW LEVEL SECURITY;
