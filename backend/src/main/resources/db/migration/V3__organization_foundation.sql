-- V3 introduces the Organization foundation: the Organization identity and the
-- Organization Membership relationship between a User and an Organization.
--
-- Source of truth: docs/domain/organization.md (APPROVED 1.5) and
-- docs/domain/organization-membership.md (APPROVED 1.4).
--
-- Workspace, invitations, additional roles/statuses, and Organization recovery
-- are deliberately out of scope for this migration. The approved rules that
-- involve the ACTIVE Organization OWNER + ACTIVE Workspace ADMIN intersection
-- remain deferred requirements for the future Workspace implementation.

-- The approved specification defines only the stable identity. No status,
-- name, or business metadata columns are specified, so none are invented here.
CREATE TABLE organizations (
    id UUID NOT NULL,
    CONSTRAINT organizations_pk PRIMARY KEY (id)
);

-- An Organization Membership is identified by its Organization and User: a
-- User cannot have more than one Membership within the same Organization, and
-- no independent Membership identity is defined by the approved specification.
-- OWNER and ACTIVE are the only role/status values currently specified; the
-- application repository writes both values explicitly, so no defaults exist.
CREATE TABLE organization_memberships (
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    role            TEXT NOT NULL,
    status          TEXT NOT NULL,
    CONSTRAINT organization_memberships_pk PRIMARY KEY (organization_id, user_id),
    CONSTRAINT organization_memberships_organization_fk FOREIGN KEY (organization_id)
        REFERENCES organizations (id) ON DELETE RESTRICT,
    CONSTRAINT organization_memberships_user_fk FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT organization_memberships_role_allowed CHECK (role IN ('OWNER')),
    CONSTRAINT organization_memberships_status_allowed CHECK (status IN ('ACTIVE'))
);

-- Invariant: a committed Organization always has at least one Organization
-- Membership with role OWNER and status ACTIVE.
--
-- The check is deferred to commit so the legitimate atomic creation
-- transaction (insert Organization, then insert initial Membership) is not
-- rejected between the two statements.
--
-- User status is deliberately NOT part of this count: an ACTIVE OWNER
-- Membership remains structurally valid even when its User later becomes
-- SUSPENDED or DEACTIVATED. Operational orphaning is a derived condition and
-- is not persisted.
CREATE FUNCTION organizations_require_active_owner() RETURNS TRIGGER AS $$
DECLARE
    owner_count INTEGER;
BEGIN
    SELECT count(*) INTO owner_count
    FROM organization_memberships
    WHERE organization_id = NEW.id AND role = 'OWNER' AND status = 'ACTIVE';
    IF owner_count < 1 THEN
        RAISE EXCEPTION
            'Organization % must have at least one ACTIVE OWNER Organization Membership, found %',
            NEW.id, owner_count
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER organizations_require_active_owner
    AFTER INSERT ON organizations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION organizations_require_active_owner();

-- Invariant: a surviving Organization cannot be left without an ACTIVE OWNER
-- Membership by updating or deleting Membership rows.
--
-- Concurrency / write-skew protection: a plain deferred COUNT is not enough.
-- With two owners A and B, transaction T1 could delete A while transaction T2
-- concurrently deletes B; under READ COMMITTED each deferred COUNT would still
-- observe the other (already deleted but uncommitted-elsewhere) owner via its
-- own snapshot, and both transactions could commit, leaving zero owners.
--
-- To prevent this, every invariant-changing Membership check is serialized
-- through the parent Organization row: the trigger first locks the affected
-- Organization row with SELECT ... FOR UPDATE. The second transaction must
-- then wait until the first has committed; only after acquiring the lock does
-- it count the current ACTIVE OWNER Memberships (the row lock forces the
-- waiter to see the committed effects of the winner), so the losing
-- transaction reliably observes zero remaining owners and is rejected. The
-- lock is deterministic and scoped to the affected Organization only.
CREATE FUNCTION organization_memberships_preserve_active_owner() RETURNS TRIGGER AS $$
DECLARE
    owner_count INTEGER;
BEGIN
    -- Lock the previous parent Organization row if it still exists. Physically
    -- removing the Organization together with its Memberships in one
    -- transaction leaves no Organization behind and does not violate the
    -- invariant.
    PERFORM 1 FROM organizations WHERE id = OLD.organization_id FOR UPDATE;
    IF FOUND THEN
        SELECT count(*) INTO owner_count
        FROM organization_memberships
        WHERE organization_id = OLD.organization_id AND role = 'OWNER' AND status = 'ACTIVE';
        IF owner_count < 1 THEN
            RAISE EXCEPTION
                'Organization % must retain at least one ACTIVE OWNER Organization Membership, found %',
                OLD.organization_id, owner_count
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER organization_memberships_preserve_active_owner
    AFTER UPDATE OR DELETE ON organization_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION organization_memberships_preserve_active_owner();

-- PostgreSQL does not fire row-level DELETE triggers for TRUNCATE, so the
-- ACTIVE OWNER invariant needs a statement-level safeguard as well.
-- This check is deliberately conditional: it only rejects a TRUNCATE that
-- leaves a surviving Organization without an ACTIVE OWNER Membership. Removing
-- all Organizations and Memberships together leaves no Organization at all and
-- therefore does not violate the invariant.
CREATE FUNCTION organization_memberships_preserve_active_owner_on_truncate() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM organizations o
        WHERE NOT EXISTS (
            SELECT 1
            FROM organization_memberships m
            WHERE m.organization_id = o.id AND m.role = 'OWNER' AND m.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION
            'TRUNCATE of organization_memberships would leave an existing Organization without an ACTIVE OWNER Organization Membership'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER organization_memberships_preserve_active_owner_on_truncate
    AFTER TRUNCATE ON organization_memberships
    FOR EACH STATEMENT
    EXECUTE FUNCTION organization_memberships_preserve_active_owner_on_truncate();
