-- The permission catalog version each role was last synced against. The startup sync grants a
-- default role only the permissions that entered the catalog after this version, so a permission
-- an owner removed stays removed across restarts.
-- Existing default roles were already synced to the whole version-1 catalog by the old sync, which
-- re-added every missing permission on each start, so they are marked as version 1: anything an
-- owner removed before this upgrade (such as user:write taken from MANAGER) stays removed.
-- New roles start at 0 and seedTenant's are synced in the same startup; custom roles are never synced.
ALTER TABLE role ADD COLUMN synced_catalog_version INT NOT NULL DEFAULT 0;

UPDATE role SET synced_catalog_version = 1 WHERE name IN ('OWNER', 'MANAGER', 'CASHIER');
