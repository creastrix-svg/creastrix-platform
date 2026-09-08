-- Preserve deferred Workspace creation semantics while avoiding FK KEY SHARE
-- lock upgrades at the three creation-only parent/User lock sites.
CREATE OR REPLACE FUNCTION public.workspaces_require_initial_foundation() RETURNS TRIGGER AS $$
DECLARE
    owner_status TEXT;
BEGIN
    IF NEW.owner_type = 'USER' THEN
        SELECT status INTO owner_status FROM users WHERE id = NEW.owner_user_id FOR NO KEY UPDATE;
        IF owner_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION
                'A newly created User-owned Workspace % requires an ACTIVE owner User %, found status %',
                NEW.id, NEW.owner_user_id, owner_status
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        PERFORM 1 FROM organizations WHERE id = NEW.owner_organization_id FOR NO KEY UPDATE;
        PERFORM 1 FROM users u
        WHERE u.id IN (
            SELECT user_id FROM workspace_memberships
            WHERE workspace_id = NEW.id AND role = 'ADMIN' AND status = 'ACTIVE'
        )
        ORDER BY u.id
        FOR NO KEY UPDATE;
        IF NOT EXISTS (
            SELECT 1
            FROM workspace_memberships wm
            JOIN organization_memberships om ON om.user_id = wm.user_id
            JOIN users u ON u.id = wm.user_id
            WHERE wm.workspace_id = NEW.id AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE'
              AND om.organization_id = NEW.owner_organization_id
              AND om.role = 'OWNER' AND om.status = 'ACTIVE'
              AND u.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION
                'A newly created Organization-owned Workspace % requires a creator who is an ACTIVE User, an ACTIVE OWNER of Organization %, and an ACTIVE ADMIN of the Workspace',
                NEW.id, NEW.owner_organization_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    PERFORM workspaces_validate_foundation(
        NEW.id, NEW.owner_type, NEW.owner_user_id, NEW.owner_organization_id);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
