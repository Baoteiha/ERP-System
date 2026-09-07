-- Refresh-token families (AUTH-G10). A token minted by rotating an existing one
-- inherits its family; presenting an already-rotated token revokes the whole
-- family, which is the signal that a token was stolen.

ALTER TABLE refresh_token ADD COLUMN family_id UUID;

-- Existing tokens each become their own single-member family.
UPDATE refresh_token SET family_id = id WHERE family_id IS NULL;

ALTER TABLE refresh_token ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_family_id ON refresh_token (family_id);
