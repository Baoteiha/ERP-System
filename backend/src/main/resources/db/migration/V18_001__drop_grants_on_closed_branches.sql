-- Deleting or deactivating a branch now drops the grants on it; remove the ones left from before.
DELETE FROM user_branch_access uba
USING branch b
WHERE uba.branch_id = b.id AND (b.deleted OR NOT b.active);
