-- Every access token carries its user's auth_version (claim av) as it stood when the token was
-- issued, and the JWT filter refuses a token whose version is no longer the user's (AUTH-G08). The
-- version moves on what the grant and role caches do not reach: a status change, a password change
-- or reset, and a branch closing, whose grants go with it. Existing users start at 0; tokens issued
-- before carry no version and are refused, so clients refresh once.
ALTER TABLE app_user ADD COLUMN auth_version INT NOT NULL DEFAULT 0;
