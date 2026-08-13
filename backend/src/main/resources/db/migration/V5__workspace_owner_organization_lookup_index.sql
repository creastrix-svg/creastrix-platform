-- V5 adds the approved lookup index for Organization-owned Workspaces.
--
-- The commit-time Organization Membership invariant trigger introduced in V4
-- selects every Organization-owned Workspace by owner_organization_id
-- (see organization_memberships_preserve_workspace_foundation), and the
-- owner Organization foreign key requires the same lookup to enforce
-- ON DELETE RESTRICT. Without a matching index both fall back to a full
-- sequential scan of workspaces.
--
-- The partial predicate indexes only Organization-owned Workspaces, because a
-- User-owned Workspace always has owner_organization_id IS NULL and is never
-- a lookup target here. No table, constraint, trigger, or function defined by
-- V1-V4 is modified, and the existing ORDER BY id / FOR UPDATE lock order is
-- unchanged.
CREATE INDEX workspaces_owner_organization_id_not_null_idx
    ON workspaces (owner_organization_id)
    WHERE owner_organization_id IS NOT NULL;
