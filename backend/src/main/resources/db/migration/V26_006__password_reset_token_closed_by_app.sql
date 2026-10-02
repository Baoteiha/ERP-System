-- A user's open password-reset links now close whenever their access or email changes (R44): a status
-- change, a password change or reset, a branch closing (with auth_version), and an email change, each in
-- the transaction that makes it. Those run as erp_app, so password_reset_token is no longer owner-only:
-- it takes the policy every child of app_user has (a row is visible while its user is), and erp_app may
-- read the columns that pick a user's open links and set used_at. It still cannot read a token's hash,
-- add a link or delete one; issuing and confirming links stay on the schema owner's connection.
CREATE POLICY password_reset_token_tenant_isolation ON password_reset_token
    USING (EXISTS (SELECT 1 FROM app_user p WHERE p.id = password_reset_token.user_id));

GRANT SELECT (user_id, used_at), UPDATE (used_at) ON password_reset_token TO erp_app;
