-- V7 adds only structural Ready-Made Product lifecycle enforcement.
-- Exactly ACTIVE -> ARCHIVED and ARCHIVED -> ACTIVE are structurally valid;
-- same-state and every other transition are rejected with SQLSTATE 23514.
--
-- Security boundary: the migration owner and runtime database role currently
-- coincide. This trigger neither receives nor proves actor identity or actor
-- authorization. A structurally valid raw SQL transition therefore remains
-- possible to a client holding runtime database credentials. The supported
-- Java/JDBC path separately validates the represented actor under locks.
-- Authentication, proven external caller identity, and database-role
-- separation remain deferred to a dedicated security slice.
--
-- This status-only trigger and the V6 immutable-identity trigger are
-- independent. Neither changes values used by the other, and correctness does
-- not depend on PostgreSQL's alphabetical ordering of same-kind triggers.
CREATE FUNCTION ready_made_products_enforce_status_transition() RETURNS TRIGGER AS $$
BEGIN
    IF NOT (
        (OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED')
        OR (OLD.status = 'ARCHIVED' AND NEW.status = 'ACTIVE')
    ) THEN
        RAISE EXCEPTION
            'Unsupported Ready-Made Product status transition for %: % -> %',
            OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_made_products_enforce_status_transition
BEFORE UPDATE OF status ON ready_made_products
FOR EACH ROW
EXECUTE FUNCTION ready_made_products_enforce_status_transition();
