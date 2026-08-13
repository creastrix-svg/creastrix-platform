-- V4 introduces the Workspace foundation: the Workspace identity with exactly
-- one immutable USER or ORGANIZATION owner, the Workspace Membership
-- relationship between a User and a Workspace, and explicit permission-scope
-- grant persistence.
--
-- Source of truth: docs/domain/workspace.md (APPROVED 1.0) and
-- docs/domain/workspace-membership.md (APPROVED 1.0).
--
-- Invitations, Membership mutation workflows, ownership transfer, Workspace
-- deletion/archival, personal Workspace recovery, and Organization recovery
-- are deliberately out of scope for this migration.

-- The approved specification defines the stable identity and exactly one
-- owner. No name, status, lifecycle, Created By, or business metadata columns
-- are specified, so none are invented here. The two nullable owner columns
-- express the exclusive USER/ORGANIZATION owner shape; the check constraint
-- makes both-owner and no-owner shapes impossible.
CREATE TABLE workspaces (
    id                    UUID NOT NULL,
    owner_type            TEXT NOT NULL,
    owner_user_id         UUID,
    owner_organization_id UUID,
    CONSTRAINT workspaces_pk PRIMARY KEY (id),
    CONSTRAINT workspaces_owner_user_fk FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT workspaces_owner_organization_fk FOREIGN KEY (owner_organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT workspaces_owner_type_allowed CHECK (owner_type IN ('USER', 'ORGANIZATION')),
    CONSTRAINT workspaces_owner_shape CHECK (
        (owner_type = 'USER' AND owner_user_id IS NOT NULL AND owner_organization_id IS NULL)
        OR (owner_type = 'ORGANIZATION' AND owner_organization_id IS NOT NULL AND owner_user_id IS NULL)
    )
);

-- A Workspace Membership is identified by its Workspace and User: a User
-- cannot have more than one Membership within the same Workspace, and no
-- independent Membership identity is defined by the approved specification.
-- The application repository writes every role and status explicitly, so no
-- defaults exist.
CREATE TABLE workspace_memberships (
    workspace_id UUID NOT NULL,
    user_id      UUID NOT NULL,
    role         TEXT NOT NULL,
    status       TEXT NOT NULL,
    CONSTRAINT workspace_memberships_pk PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT workspace_memberships_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT workspace_memberships_user_fk FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT workspace_memberships_role_allowed CHECK (role IN ('ADMIN', 'EDITOR', 'VIEWER')),
    CONSTRAINT workspace_memberships_status_allowed CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED'))
);

-- Explicit persistence of a Workspace Membership's granted permission scopes.
-- This is a relational collection table for the Membership's scope set, not a
-- new domain entity. Exactly the recognized scopes are storable; unknown and
-- future values are rejected, and no future scope can be pre-granted.
CREATE TABLE workspace_membership_scopes (
    workspace_id UUID NOT NULL,
    user_id      UUID NOT NULL,
    scope        TEXT NOT NULL,
    CONSTRAINT workspace_membership_scopes_pk PRIMARY KEY (workspace_id, user_id, scope),
    CONSTRAINT workspace_membership_scopes_membership_fk FOREIGN KEY (workspace_id, user_id)
        REFERENCES workspace_memberships (workspace_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT workspace_membership_scopes_scope_allowed
        CHECK (scope IN ('PROJECTS', 'READY_MADE_PRODUCTS', 'LISTINGS'))
);

-- Shared permanent structural foundation validation for one Workspace.
--
-- Every surviving Workspace must retain at least one ACTIVE ADMIN Membership.
-- A User-owned Workspace must additionally retain its immutable owner User as
-- an ACTIVE ADMIN. An Organization-owned Workspace must retain at least one
-- User who simultaneously has an ACTIVE OWNER Organization Membership in the
-- owning Organization and an ACTIVE ADMIN Workspace Membership.
--
-- User account status is deliberately NOT part of these permanent counts: the
-- structural Memberships remain valid when their Users later become SUSPENDED
-- or DEACTIVATED. Operational orphaning is a derived condition and is not
-- persisted.
--
-- Callers must already hold the appropriate row locks (see the lock protocol
-- described on the triggers below) before invoking this function.
CREATE FUNCTION workspaces_validate_foundation(
    w_id UUID, w_owner_type TEXT, w_owner_user_id UUID, w_owner_organization_id UUID
) RETURNS VOID AS $$
BEGIN
    -- The owner-type-specific rule is checked first so its precise message is
    -- reported; for a User-owned Workspace it also implies the generic ACTIVE
    -- ADMIN requirement.
    IF w_owner_type = 'USER' THEN
        IF NOT EXISTS (
            SELECT 1 FROM workspace_memberships
            WHERE workspace_id = w_id AND user_id = w_owner_user_id
              AND role = 'ADMIN' AND status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION
                'User-owned Workspace % must retain its owner User % as an ACTIVE ADMIN Workspace Membership',
                w_id, w_owner_user_id
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM workspace_memberships wm
            JOIN organization_memberships om ON om.user_id = wm.user_id
            WHERE wm.workspace_id = w_id AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE'
              AND om.organization_id = w_owner_organization_id
              AND om.role = 'OWNER' AND om.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION
                'Organization-owned Workspace % must retain at least one User who is both an ACTIVE OWNER of Organization % and an ACTIVE ADMIN of the Workspace',
                w_id, w_owner_organization_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM workspace_memberships
        WHERE workspace_id = w_id AND role = 'ADMIN' AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'Workspace % must retain at least one ACTIVE ADMIN Workspace Membership', w_id
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Invariant: at commit, every newly inserted Workspace has a valid initial
-- foundation.
--
-- USER ownership: the owner User exists and is ACTIVE at the linearized
-- creation point and holds an ACTIVE ADMIN Membership in the new Workspace.
-- ORGANIZATION ownership: the owning Organization exists and at least one User
-- is simultaneously an ACTIVE User, an ACTIVE OWNER of the owning
-- Organization, and an ACTIVE ADMIN of the new Workspace. The application
-- inserts exactly the creator as the initial ADMIN.
--
-- The check is deferred to commit so the legitimate atomic creation
-- transaction (insert Workspace, then insert initial Membership) is not
-- rejected between the two statements.
--
-- Concurrency / lock protocol: creation-time ACTIVE checks must be linearized
-- against concurrent User status changes and concurrent Organization OWNER
-- Membership removal. For a USER-owned Workspace the owner User row is locked
-- FOR UPDATE, serializing against the User status UPDATE. For an
-- ORGANIZATION-owned Workspace the owning Organization row is locked FOR
-- UPDATE first (the same lock every Organization Membership invariant change
-- takes), then the ADMIN member User rows are locked in deterministic UUID
-- order before the intersection is validated against current committed state.
--
-- Scope of the concurrency guarantee: the implemented scoped locks close the
-- specifically tested write-skew races, namely concurrent removal of the last
-- ACTIVE ADMIN alternatives, cross-table Organization OWNER / Workspace ADMIN
-- write skew, Organization-owned Workspace creation versus removal of the
-- creator's Organization OWNER Membership, and creation-time ACTIVE User
-- validation versus a concurrent User status change.
--
-- This foundation does NOT claim universal deadlock freedom for arbitrary
-- future transactions that combine Membership mutations across multiple
-- domains or resources. A transaction that changes both a Workspace
-- Membership and an Organization Membership can request these row locks in an
-- order opposite to another concurrent transaction, and PostgreSQL may then
-- abort one of them with SQLSTATE 40P01. The structural invariants stay safe,
-- because an aborted transaction changes nothing; only availability is
-- affected. Future Membership mutation workflows must therefore define a
-- canonical ordering of all affected Organizations and Workspaces and must
-- safely retry the complete transaction after a deadlock. No such general
-- mutation workflow is implemented in this slice, and no retry behavior is
-- implemented here.
CREATE FUNCTION workspaces_require_initial_foundation() RETURNS TRIGGER AS $$
DECLARE
    owner_status TEXT;
BEGIN
    IF NEW.owner_type = 'USER' THEN
        SELECT status INTO owner_status FROM users WHERE id = NEW.owner_user_id FOR UPDATE;
        IF owner_status IS DISTINCT FROM 'ACTIVE' THEN
            RAISE EXCEPTION
                'A newly created User-owned Workspace % requires an ACTIVE owner User %, found status %',
                NEW.id, NEW.owner_user_id, owner_status
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        PERFORM 1 FROM organizations WHERE id = NEW.owner_organization_id FOR UPDATE;
        PERFORM 1 FROM users u
        WHERE u.id IN (
            SELECT user_id FROM workspace_memberships
            WHERE workspace_id = NEW.id AND role = 'ADMIN' AND status = 'ACTIVE'
        )
        ORDER BY u.id
        FOR UPDATE;
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

CREATE CONSTRAINT TRIGGER workspaces_require_initial_foundation
    AFTER INSERT ON workspaces
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION workspaces_require_initial_foundation();

-- Invariant: Workspace identity and ownership are immutable in MVP. A
-- same-value update is not an ownership change and passes.
CREATE FUNCTION workspaces_forbid_owner_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.owner_type IS DISTINCT FROM OLD.owner_type
        OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
        OR NEW.owner_organization_id IS DISTINCT FROM OLD.owner_organization_id THEN
        RAISE EXCEPTION
            'Workspace % identity and ownership are immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspaces_forbid_owner_change
    BEFORE UPDATE ON workspaces
    FOR EACH ROW
    EXECUTE FUNCTION workspaces_forbid_owner_change();

-- Invariant: Workspace deletion and archival are unsupported in MVP. There is
-- no lifecycle or archive column and no soft deletion.
CREATE FUNCTION workspaces_forbid_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Workspace % cannot be deleted: Workspace deletion and archival are unsupported in MVP',
        OLD.id
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspaces_forbid_delete
    BEFORE DELETE ON workspaces
    FOR EACH ROW
    EXECUTE FUNCTION workspaces_forbid_delete();

-- Row-level DELETE triggers do not protect TRUNCATE, and TRUNCATE ... CASCADE
-- from a referenced table would also bypass them, so Workspace removal by
-- TRUNCATE is rejected unconditionally at statement level.
CREATE FUNCTION workspaces_forbid_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'TRUNCATE of workspaces is not supported: Workspace deletion and archival are unsupported in MVP'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspaces_forbid_truncate
    AFTER TRUNCATE ON workspaces
    FOR EACH STATEMENT
    EXECUTE FUNCTION workspaces_forbid_truncate();

-- Invariant: a surviving Workspace cannot lose its permanent structural
-- foundation through UPDATE or DELETE of Workspace Membership rows.
--
-- Concurrency / write-skew protection: a plain deferred COUNT is not enough.
-- With two ACTIVE ADMINs A and B, transaction T1 could remove A while
-- transaction T2 concurrently removes B; under READ COMMITTED each deferred
-- COUNT would still observe the other (already removed but
-- uncommitted-elsewhere) ADMIN via its own snapshot, and both transactions
-- could commit, leaving zero ADMINs.
--
-- To prevent this, every invariant-changing Membership check is serialized
-- through the affected parent Workspace row: the trigger first locks the
-- Workspace row with SELECT ... FOR UPDATE. A concurrent transaction must then
-- wait until the first has committed; only after acquiring the lock does it
-- validate the current committed state, so the losing transaction reliably
-- observes the reduced foundation and is rejected. The same Workspace row lock
-- also serializes against Organization Membership changes, whose trigger locks
-- every affected Organization-owned Workspace row (see below), which closes
-- the cross-table OWNER/ADMIN write-skew race. The lock is deterministic and
-- scoped to the affected Workspace only. As documented above, closing these
-- tested races is not a universal deadlock-freedom guarantee for arbitrary
-- future multi-Membership transactions.
CREATE FUNCTION workspace_memberships_preserve_foundation() RETURNS TRIGGER AS $$
DECLARE
    ws RECORD;
BEGIN
    SELECT id, owner_type, owner_user_id, owner_organization_id INTO ws
    FROM workspaces WHERE id = OLD.workspace_id FOR UPDATE;
    IF FOUND THEN
        PERFORM workspaces_validate_foundation(
            ws.id, ws.owner_type, ws.owner_user_id, ws.owner_organization_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER workspace_memberships_preserve_foundation
    AFTER UPDATE OR DELETE ON workspace_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION workspace_memberships_preserve_foundation();

-- Bidirectional enforcement of the Organization OWNER / Workspace ADMIN
-- intersection: UPDATE or DELETE of an Organization Membership must validate
-- every affected Workspace owned by that Organization. V3 is not modified;
-- this V4 trigger is added on the existing V3 table.
--
-- Lock protocol: the Organization row is locked first (the same lock V3's
-- last-owner trigger takes), then every affected Organization-owned Workspace
-- row is locked in deterministic UUID order. This serializes against
-- concurrent Workspace Membership changes (which lock the Workspace row) and
-- against concurrent Organization-owned Workspace creation (which locks the
-- Organization row), while remaining scoped to the affected rows. Different
-- Workspaces are validated independently, so different Workspaces may rely on
-- different replacement Users.
--
-- This ordering is not a global deadlock-freedom guarantee either: a
-- transaction that also changes Workspace Memberships may already hold a
-- Workspace row lock before this trigger requests the Organization row, so
-- PostgreSQL may abort one such transaction with SQLSTATE 40P01. See the
-- scope-of-guarantee note on workspaces_require_initial_foundation above.
CREATE FUNCTION organization_memberships_preserve_workspace_foundation() RETURNS TRIGGER AS $$
DECLARE
    ws RECORD;
BEGIN
    PERFORM 1 FROM organizations WHERE id = OLD.organization_id FOR UPDATE;
    IF FOUND THEN
        FOR ws IN
            SELECT id, owner_type, owner_user_id, owner_organization_id
            FROM workspaces
            WHERE owner_type = 'ORGANIZATION' AND owner_organization_id = OLD.organization_id
            ORDER BY id
            FOR UPDATE
        LOOP
            PERFORM workspaces_validate_foundation(
                ws.id, ws.owner_type, ws.owner_user_id, ws.owner_organization_id);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER organization_memberships_preserve_workspace_foundation
    AFTER UPDATE OR DELETE ON organization_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION organization_memberships_preserve_workspace_foundation();

-- PostgreSQL does not fire row-level DELETE triggers for TRUNCATE, so the
-- Workspace foundation needs statement-level safeguards as well.
--
-- Workspace Memberships cannot be truncated while any Workspace survives:
-- every Workspace requires at least one ACTIVE ADMIN Membership. Because
-- Workspaces themselves can never be removed, this effectively rejects any
-- TRUNCATE of workspace_memberships while Workspaces exist.
CREATE FUNCTION workspace_memberships_preserve_foundation_on_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM workspaces) THEN
        RAISE EXCEPTION
            'TRUNCATE of workspace_memberships would leave an existing Workspace without an ACTIVE ADMIN Workspace Membership'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_memberships_preserve_foundation_on_truncate
    AFTER TRUNCATE ON workspace_memberships
    FOR EACH STATEMENT
    EXECUTE FUNCTION workspace_memberships_preserve_foundation_on_truncate();

-- TRUNCATE of organization_memberships would silently break the Organization
-- OWNER / Workspace ADMIN intersection of surviving Organization-owned
-- Workspaces. V3's existing TRUNCATE safeguard (last ACTIVE OWNER) remains in
-- place; this additional V4 guard protects the Workspace-side invariant. The
-- check is conditional: it only rejects a TRUNCATE that leaves a surviving
-- Organization-owned Workspace without the required intersection.
--
-- No TRUNCATE guard exists for workspace_membership_scopes: removing all
-- explicit scope grants violates no structural invariant.
CREATE FUNCTION organization_memberships_preserve_workspaces_on_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM workspaces w
        WHERE w.owner_type = 'ORGANIZATION'
          AND NOT EXISTS (
            SELECT 1
            FROM workspace_memberships wm
            JOIN organization_memberships om ON om.user_id = wm.user_id
            WHERE wm.workspace_id = w.id AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE'
              AND om.organization_id = w.owner_organization_id
              AND om.role = 'OWNER' AND om.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION
            'TRUNCATE of organization_memberships would leave an existing Organization-owned Workspace without its required ACTIVE OWNER / ACTIVE ADMIN User'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER organization_memberships_preserve_workspaces_on_truncate
    AFTER TRUNCATE ON organization_memberships
    FOR EACH STATEMENT
    EXECUTE FUNCTION organization_memberships_preserve_workspaces_on_truncate();
