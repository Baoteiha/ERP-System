-- The refresh token moved from the response body into an HttpOnly cookie (FE-14). Tokens issued before
-- this deploy were handed out in a body and may sit in a script-readable store the new frontend never
-- reads again; they would stay valid for their whole 30 days. Revoke every live one, so everyone signs
-- in once. The version moves with it, as the application's own revocation does.
UPDATE refresh_token SET revoked = TRUE, version = version + 1 WHERE revoked = FALSE;
