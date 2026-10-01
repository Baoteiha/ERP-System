-- The permission catalog version each role was last synced against. The startup sync grants a
-- default role only the permissions that entered the catalog after this version, so a permission
-- an owner removed stays removed across restarts.
-- Existing roles start at 0: their first start on this version offers them the whole catalog once
-- more (as every start did before), so a deployment that never ran the earlier sync still gets
-- its default permissions. From then on, removals stick.
ALTER TABLE role ADD COLUMN synced_catalog_version INT NOT NULL DEFAULT 0;
