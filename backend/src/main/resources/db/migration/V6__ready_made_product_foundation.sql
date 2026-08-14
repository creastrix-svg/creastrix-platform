-- V6 introduces the Ready-Made Product structural foundation: the stable
-- product identity, its immutable Workspace relationship, its immutable
-- Created By User, the ACTIVE/ARCHIVED lifecycle state, and the simple
-- non-negative available quantity.
--
-- Source of truth: docs/domain/ready-made-product.md (APPROVED 1.0).
--
-- This migration deliberately covers only the structural foundation and safe
-- creation. Lifecycle transition workflows, manual quantity deltas, command
-- identity and idempotency persistence, Order Item allocation, pre-dispatch
-- release, Shipment serialization, Listing, and every other commerce concern
-- are out of scope. Those specifications either remain DRAFT or are not
-- implemented yet, so no persistence for them is invented here.

-- The approved specification defines exactly one stable identity, exactly one
-- Workspace, exactly one immutable Created By User, exactly one lifecycle
-- state, and the available quantity. No name, description, SKU, brand, media,
-- dimension, timestamp, or metadata column is specified, so none is invented
-- here. Every value is written explicitly by the application repository, so no
-- database default exists. Both foreign keys use RESTRICT: no Ready-Made
-- Product reference may ever be removed by cascade.
CREATE TABLE ready_made_products (
    id                 UUID   NOT NULL,
    workspace_id       UUID   NOT NULL,
    created_by_user_id UUID   NOT NULL,
    status             TEXT   NOT NULL,
    available_quantity BIGINT NOT NULL,
    CONSTRAINT ready_made_products_pk PRIMARY KEY (id),
    CONSTRAINT ready_made_products_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT ready_made_products_created_by_user_fk FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ready_made_products_status_allowed CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ready_made_products_available_quantity_non_negative
        CHECK (available_quantity >= 0)
);

-- No index is created here. The only queries introduced by this slice are
-- primary-key lookups, which the primary-key index already serves. Listing,
-- Order Item, and Workspace-scoped listing queries do not exist yet, so any
-- further index would be speculative.

-- Invariant: at commit, every newly inserted Ready-Made Product is a valid
-- creation result.
--
-- The database independently enforces, against a direct SQL INSERT that
-- bypasses the application service, that:
--   * the initial lifecycle state is ACTIVE (ARCHIVED creation is impossible);
--   * the represented Created By User has an ACTIVE Workspace Membership in
--     the product's Workspace;
--   * that Membership has effective READY_MADE_PRODUCTS write authorization,
--     which means ADMIN without any stored scope grant, or EDITOR with an
--     explicit READY_MADE_PRODUCTS grant, and never VIEWER;
--   * the represented Created By User is ACTIVE at the linearized creation
--     point.
-- The Workspace and the Created By User must exist: that is already guaranteed
-- by the two RESTRICT foreign keys.
--
-- Identity boundary: created_by_user_id is the creator identity represented to
-- the platform. Authentication does not exist yet, so this gate does not prove
-- anything about an external HTTP caller. It only proves that the represented
-- creator identity actually holds the required authorization at the linearized
-- creation point.
--
-- Two-phase enforcement, and why the phases are ordered this way.
--
-- Phase 1 runs in a BEFORE INSERT row trigger, that is BEFORE the row is
-- inserted and therefore BEFORE PostgreSQL takes the implicit referential
-- integrity KEY SHARE row locks on the parent workspaces and users rows. This
-- phase acquires, in this fixed order, an explicit FOR UPDATE lock on the
-- creator's Workspace Membership row, then (for EDITOR only) on the exact
-- READY_MADE_PRODUCTS permission-scope grant row, then on the creator's User
-- row, and validates the actual creation authorization against the state it
-- just locked.
--
-- Phase 2 runs in a DEFERRABLE INITIALLY DEFERRED AFTER INSERT constraint
-- trigger at commit and re-validates the final state. It deliberately acquires
-- NO new row locks: the phase 1 locks are held until the transaction ends, so
-- no concurrent transaction can change the authorization rows in between, and
-- a plain read is sufficient to detect any later change made inside the same
-- transaction. Because phase 2 requests no lock, it introduces no opposing
-- lock order.
--
-- Why phase 1 must precede the insert (real V4 interaction): the already
-- integrated V4 deferred constraint trigger
-- workspace_memberships_preserve_foundation fires at commit for every
-- Workspace Membership UPDATE or DELETE and requests the parent workspaces row
-- FOR UPDATE while still holding the changed Membership row lock. If Ready-Made
-- Product creation validated only at commit, it would already hold the implicit
-- FK KEY SHARE lock on that same workspaces row from its own INSERT and would
-- then wait for the Membership row, producing a genuine lock cycle that
-- PostgreSQL resolves by aborting one transaction with SQLSTATE 40P01. Doing
-- the authorization locking before the INSERT removes the cycle:
--   * if a Membership mutation already holds the Membership row, the creation
--     transaction waits for it while holding no workspaces lock at all, so the
--     mutation can run its V4 workspaces validation and commit; the creation
--     then re-reads the now committed Membership state, sees the lost
--     authorization and is rejected with SQLSTATE 23514, leaving no row;
--   * if the creation acquires the authorization locks first, the Membership
--     mutation waits, the creation commits, and only then can the mutation
--     commit; the Ready-Made Product survives as a historically valid creation.
-- V4 is not modified and no existing invariant workflow changes its lock order.
--
-- Honest scope of the lock documentation: the transaction lock set is NOT only
-- Membership -> scope grant -> User. A Ready-Made Product INSERT additionally
-- takes implicit FK KEY SHARE locks on the referenced workspaces and users
-- rows, and it takes them after the explicit phase 1 locks. The trigger takes
-- no explicit workspaces and no organizations lock.
--
-- Scope of the concurrency guarantee: the protocol closes the specifically
-- tested Ready-Made Product creation races, namely creation after an
-- application-level read of an ACTIVE creator versus concurrent User suspension
-- or deactivation, and creation after an application-level read of the
-- authorization versus concurrent revocation of the READY_MADE_PRODUCTS grant,
-- Membership suspension, Membership role downgrade to VIEWER, or Membership
-- deletion. As already documented in V4, this is not a universal
-- deadlock-freedom claim: an arbitrary future multi-resource transaction that
-- combines Ready-Made Product creation with User, Membership, Workspace, or
-- Organization mutations may still request rows in an opposing order, and
-- PostgreSQL may then abort one transaction with SQLSTATE 40P01. Structural
-- safety is unaffected, because an aborted transaction changes nothing.
--
-- Creation authorization is a creation-time gate only. It is deliberately NOT
-- re-validated for a surviving Ready-Made Product: a later User suspension or
-- Membership or scope revocation never deletes, rewrites, or invalidates an
-- already created historical Ready-Made Product or its Created By.
CREATE FUNCTION ready_made_products_validate_creation(
    product_id UUID,
    workspace_id UUID,
    created_by_user_id UUID,
    product_status TEXT,
    acquire_locks BOOLEAN) RETURNS VOID AS $$
DECLARE
    membership RECORD;
    creator_status TEXT;
    scope_found BOOLEAN;
BEGIN
    IF product_status <> 'ACTIVE' THEN
        RAISE EXCEPTION
            'A newly created Ready-Made Product % requires the initial lifecycle state ACTIVE, found %',
            product_id, product_status
            USING ERRCODE = 'check_violation';
    END IF;

    IF acquire_locks THEN
        SELECT wm.role, wm.status INTO membership
        FROM workspace_memberships wm
        WHERE wm.workspace_id = ready_made_products_validate_creation.workspace_id
          AND wm.user_id = ready_made_products_validate_creation.created_by_user_id
        FOR UPDATE;
    ELSE
        SELECT wm.role, wm.status INTO membership
        FROM workspace_memberships wm
        WHERE wm.workspace_id = ready_made_products_validate_creation.workspace_id
          AND wm.user_id = ready_made_products_validate_creation.created_by_user_id;
    END IF;
    IF membership IS NULL THEN
        RAISE EXCEPTION
            'Ready-Made Product % creation requires an ACTIVE Workspace Membership with effective READY_MADE_PRODUCTS write authorization, but Created By User % has no Workspace Membership in Workspace %',
            product_id, created_by_user_id, workspace_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF membership.status <> 'ACTIVE' THEN
        RAISE EXCEPTION
            'Ready-Made Product % creation requires an ACTIVE Workspace Membership for Created By User % in Workspace %, found Membership status %',
            product_id, created_by_user_id, workspace_id, membership.status
            USING ERRCODE = 'check_violation';
    END IF;

    IF membership.role = 'VIEWER' THEN
        RAISE EXCEPTION
            'Ready-Made Product % creation requires effective READY_MADE_PRODUCTS write authorization, but the VIEWER Workspace Membership of User % in Workspace % never grants write access',
            product_id, created_by_user_id, workspace_id
            USING ERRCODE = 'check_violation';
    ELSIF membership.role = 'EDITOR' THEN
        IF acquire_locks THEN
            SELECT true INTO scope_found
            FROM workspace_membership_scopes wms
            WHERE wms.workspace_id = ready_made_products_validate_creation.workspace_id
              AND wms.user_id = ready_made_products_validate_creation.created_by_user_id
              AND wms.scope = 'READY_MADE_PRODUCTS'
            FOR UPDATE;
        ELSE
            SELECT true INTO scope_found
            FROM workspace_membership_scopes wms
            WHERE wms.workspace_id = ready_made_products_validate_creation.workspace_id
              AND wms.user_id = ready_made_products_validate_creation.created_by_user_id
              AND wms.scope = 'READY_MADE_PRODUCTS';
        END IF;
        IF scope_found IS NOT TRUE THEN
            RAISE EXCEPTION
                'Ready-Made Product % creation by the EDITOR Workspace Membership of User % in Workspace % requires an explicit READY_MADE_PRODUCTS permission scope grant',
                product_id, created_by_user_id, workspace_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    -- ADMIN needs no stored scope grant: its current-scope write authority
    -- comes from the role itself (Workspace Membership APPROVED 1.0).

    IF acquire_locks THEN
        SELECT u.status INTO creator_status FROM users u
        WHERE u.id = ready_made_products_validate_creation.created_by_user_id
        FOR UPDATE;
    ELSE
        SELECT u.status INTO creator_status FROM users u
        WHERE u.id = ready_made_products_validate_creation.created_by_user_id;
    END IF;
    IF creator_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION
            'A newly created Ready-Made Product % requires an ACTIVE Created By User %, found status %',
            product_id, created_by_user_id, creator_status
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Phase 1: lock the authorization rows and validate, before the row exists and
-- therefore before any implicit foreign-key row lock is taken.
CREATE FUNCTION ready_made_products_require_valid_creation() RETURNS TRIGGER AS $$
BEGIN
    PERFORM ready_made_products_validate_creation(
        NEW.id, NEW.workspace_id, NEW.created_by_user_id, NEW.status, true);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_made_products_require_valid_creation
    BEFORE INSERT ON ready_made_products
    FOR EACH ROW
    EXECUTE FUNCTION ready_made_products_require_valid_creation();

-- Phase 2: final commit-time revalidation of the same creation authorization,
-- without acquiring any new row lock.
CREATE FUNCTION ready_made_products_revalidate_creation() RETURNS TRIGGER AS $$
BEGIN
    PERFORM ready_made_products_validate_creation(
        NEW.id, NEW.workspace_id, NEW.created_by_user_id, NEW.status, false);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ready_made_products_revalidate_creation
    AFTER INSERT ON ready_made_products
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ready_made_products_revalidate_creation();

-- Invariant: Ready-Made Product identity, its Workspace relationship, and its
-- Created By User are immutable in MVP. Lifecycle status and available
-- quantity remain independently changeable values, so a valid stored ACTIVE or
-- ARCHIVED state and any valid non-negative quantity may be persisted; no
-- application mutation for them exists in this slice. A same-value update is
-- not a change and passes.
CREATE FUNCTION ready_made_products_forbid_identity_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION
            'Ready-Made Product % identity is immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.workspace_id IS DISTINCT FROM OLD.workspace_id THEN
        RAISE EXCEPTION
            'The Workspace of Ready-Made Product % is immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id THEN
        RAISE EXCEPTION
            'The Created By User of Ready-Made Product % is immutable', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_made_products_forbid_identity_change
    BEFORE UPDATE ON ready_made_products
    FOR EACH ROW
    EXECUTE FUNCTION ready_made_products_forbid_identity_change();

-- Invariant: a Ready-Made Product is never destructively deleted in MVP,
-- whether ACTIVE, ARCHIVED, listed, purchased, allocated, or unused. The rule
-- is unconditional: it uses no predicate on Listing count, Order Item count,
-- quantity, or allocation state. ARCHIVED remains the retained non-active
-- lifecycle state.
CREATE FUNCTION ready_made_products_forbid_delete() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'Ready-Made Product % cannot be deleted: destructive deletion is unsupported in MVP regardless of lifecycle state, stock, or commercial references',
        OLD.id
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_made_products_forbid_delete
    BEFORE DELETE ON ready_made_products
    FOR EACH ROW
    EXECUTE FUNCTION ready_made_products_forbid_delete();

-- Row-level DELETE triggers do not protect TRUNCATE, and TRUNCATE ... CASCADE
-- from a referenced table would also bypass them, so removal by TRUNCATE is
-- rejected unconditionally at statement level.
CREATE FUNCTION ready_made_products_forbid_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'TRUNCATE of ready_made_products is not supported: destructive deletion of a Ready-Made Product is unsupported in MVP'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_made_products_forbid_truncate
    AFTER TRUNCATE ON ready_made_products
    FOR EACH STATEMENT
    EXECUTE FUNCTION ready_made_products_forbid_truncate();
