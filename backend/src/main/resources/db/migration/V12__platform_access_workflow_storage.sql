-- Exploratory I2A-S storage only: no seeds, actor-authorized writer, HTTP path,
-- maintenance workflow or live privilege is introduced by this migration.
-- All objects, backfill and Flyway history must share the migration transaction.
DO $$
BEGIN
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_MIGRATION_ISOLATION_V1: READ COMMITTED required';
    END IF;
END;
$$;

LOCK TABLE ONLY public.users IN SHARE ROW EXCLUSIVE MODE;

-- This separate RC command sees writers which completed while the barrier waited.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_catalog.pg_locks
        WHERE pid = pg_catalog.pg_backend_pid() AND locktype = 'relation'
          AND relation = 'public.users'::regclass
          AND mode = 'ShareRowExclusiveLock' AND granted
    ) OR EXISTS (
        SELECT 1 FROM pg_catalog.pg_inherits WHERE inhparent = 'public.users'::regclass
    ) THEN
        RAISE EXCEPTION USING ERRCODE = '55000',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_MIGRATION_BOUNDARY_V1: unsupported users boundary';
    END IF;
    IF EXISTS (SELECT 1 FROM public.users WHERE status NOT IN ('ACTIVE', 'SUSPENDED', 'DEACTIVATED')) THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_MIGRATION_VALIDATION_V1: invalid User status';
    END IF;
END;
$$;

ALTER TABLE public.users ADD COLUMN account_eligibility_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE public.users ADD CONSTRAINT users_account_eligibility_generation_positive
    CHECK (account_eligibility_generation > 0);

CREATE FUNCTION public.users_enforce_initial_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_eligibility_generation IS DISTINCT FROM 1::bigint THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'users_initial_eligibility_generation',
            MESSAGE = 'CREASTRIX_USER_GENERATION_V1: initial generation must be one';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION public.users_reject_generation_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'users_eligibility_generation_assignment',
        MESSAGE = 'CREASTRIX_USER_GENERATION_V1: explicit generation assignment is forbidden';
END;
$$;

CREATE FUNCTION public.users_increment_eligibility_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- The unchanged V2 status guard runs first and rejects invalid/no-op transitions.
    IF OLD.account_eligibility_generation = 9223372036854775807 THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            CONSTRAINT = 'users_eligibility_generation_overflow',
            MESSAGE = 'CREASTRIX_USER_GENERATION_V1: generation exhausted';
    END IF;
    NEW.account_eligibility_generation := OLD.account_eligibility_generation + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_enforce_initial_generation BEFORE INSERT ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.users_enforce_initial_generation();
CREATE TRIGGER users_reject_generation_assignment
    BEFORE UPDATE OF account_eligibility_generation ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.users_reject_generation_assignment();
CREATE TRIGGER users_increment_eligibility_generation BEFORE UPDATE OF status ON public.users
    FOR EACH ROW EXECUTE FUNCTION public.users_increment_eligibility_generation();

CREATE TABLE public.platform_access_grants (
    id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    role VARCHAR(64) NOT NULL,
    catalog_version INTEGER NOT NULL,
    bundle_version INTEGER NOT NULL,
    starts_at TIMESTAMPTZ(6) NOT NULL,
    validity_kind VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ(6),
    scope_kind VARCHAR(64) NOT NULL,
    scope_target_count INTEGER NOT NULL,
    issued_by_user_id UUID NOT NULL,
    issued_at TIMESTAMPTZ(6) NOT NULL,
    state VARCHAR(64) NOT NULL DEFAULT 'ACTIVE',
    revision BIGINT NOT NULL DEFAULT 1,
    last_changed_by_user_id UUID,
    last_changed_at TIMESTAMPTZ(6),
    revoked_by_user_id UUID,
    revoked_at TIMESTAMPTZ(6),
    CONSTRAINT platform_access_grants_pk PRIMARY KEY (id),
    CONSTRAINT platform_access_grants_id_recipient_unique UNIQUE (id, recipient_user_id),
    CONSTRAINT platform_access_grants_recipient_fk FOREIGN KEY (recipient_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_grants_issuer_fk FOREIGN KEY (issued_by_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_grants_change_actor_fk FOREIGN KEY (last_changed_by_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_grants_revoke_actor_fk FOREIGN KEY (revoked_by_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_grants_role_allowed CHECK (role IN ('PLATFORM_OWNER', 'SUPPORT_READ')),
    CONSTRAINT platform_access_grants_versions CHECK (catalog_version = 1 AND bundle_version = 1),
    CONSTRAINT platform_access_grants_state_allowed CHECK (state IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT platform_access_grants_revision_positive CHECK (revision > 0),
    CONSTRAINT platform_access_grants_finite_times CHECK (
        isfinite(starts_at) AND isfinite(issued_at)
        AND (expires_at IS NULL OR isfinite(expires_at))
        AND (last_changed_at IS NULL OR isfinite(last_changed_at))
        AND (revoked_at IS NULL OR isfinite(revoked_at))),
    CONSTRAINT platform_access_grants_validity_shape CHECK (
        starts_at = issued_at AND (
            (validity_kind = 'UNBOUNDED' AND expires_at IS NULL AND role = 'PLATFORM_OWNER')
            OR (validity_kind = 'BOUNDED' AND expires_at IS NOT NULL AND expires_at > starts_at
                AND (role = 'PLATFORM_OWNER' OR expires_at - issued_at <= INTERVAL '720 hours')))),
    CONSTRAINT platform_access_grants_scope_shape CHECK (
        (role = 'PLATFORM_OWNER' AND scope_kind = 'PLATFORM_SECURITY_METADATA' AND scope_target_count = 0)
        OR (role = 'SUPPORT_READ' AND scope_kind = 'EXACT_USERS' AND scope_target_count BETWEEN 1 AND 100)),
    CONSTRAINT platform_access_grants_change_shape CHECK (
        (revision = 1 AND last_changed_by_user_id IS NULL AND last_changed_at IS NULL)
        OR (revision > 1 AND last_changed_by_user_id IS NOT NULL AND last_changed_at IS NOT NULL
            AND last_changed_at >= issued_at)),
    CONSTRAINT platform_access_grants_revoke_shape CHECK (
        (state <> 'REVOKED' AND revoked_by_user_id IS NULL AND revoked_at IS NULL)
        OR (state = 'REVOKED' AND revoked_by_user_id IS NOT NULL AND revoked_at IS NOT NULL
            AND revoked_by_user_id = last_changed_by_user_id AND revoked_at = last_changed_at))
);

-- Expiry and User status deliberately do not affect occupation of the slot.
CREATE UNIQUE INDEX platform_access_grants_single_nonrevoked
    ON public.platform_access_grants (recipient_user_id) WHERE state <> 'REVOKED';

CREATE TABLE public.platform_access_grant_targets (
    grant_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    CONSTRAINT platform_access_grant_targets_pk PRIMARY KEY (grant_id, target_user_id),
    CONSTRAINT platform_access_grant_targets_grant_fk FOREIGN KEY (grant_id)
        REFERENCES public.platform_access_grants (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_grant_targets_user_fk FOREIGN KEY (target_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

CREATE TABLE public.platform_access_operation_intents (
    initiator_user_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    intent_version INTEGER NOT NULL,
    kind VARCHAR(64) NOT NULL,
    target_user_id UUID NOT NULL,
    target_grant_id UUID,
    read_reason VARCHAR(64),
    expected_revision BIGINT,
    registered_at TIMESTAMPTZ(6) NOT NULL,
    correlation_id UUID NOT NULL,
    actor_grant_id UUID NOT NULL,
    actor_grant_revision BIGINT NOT NULL,
    CONSTRAINT platform_access_operation_intents_pk PRIMARY KEY (initiator_user_id, operation_id),
    CONSTRAINT platform_access_operation_intents_initiator_fk FOREIGN KEY (initiator_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_operation_intents_target_user_fk FOREIGN KEY (target_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_operation_intents_target_grant_fk FOREIGN KEY (target_grant_id, target_user_id)
        REFERENCES public.platform_access_grants (id, recipient_user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_operation_intents_actor_grant_fk FOREIGN KEY (actor_grant_id, initiator_user_id)
        REFERENCES public.platform_access_grants (id, recipient_user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_operation_intents_version CHECK (intent_version = 1),
    CONSTRAINT platform_access_operation_intents_actor_revision CHECK (actor_grant_revision > 0),
    CONSTRAINT platform_access_operation_intents_finite_time CHECK (isfinite(registered_at)),
    CONSTRAINT platform_access_operation_intents_shape CHECK (
        (kind = 'USER_SECURITY_READ' AND target_grant_id IS NULL
            AND read_reason IS NOT NULL AND read_reason IN ('USER_REQUESTED_SUPPORT', 'SECURITY_REVIEW')
            AND expected_revision IS NULL)
        OR (kind = 'SUPPORT_REVOKE' AND target_grant_id IS NOT NULL AND read_reason IS NULL
            AND expected_revision IS NOT NULL AND expected_revision > 0))
);

CREATE TABLE public.platform_access_audit_events (
    id UUID NOT NULL,
    event_version INTEGER NOT NULL,
    event_kind VARCHAR(64) NOT NULL,
    event_at TIMESTAMPTZ(6) NOT NULL,
    correlation_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    actor_user_id UUID,
    actor_grant_id UUID,
    actor_grant_revision BIGINT,
    actor_role VARCHAR(64),
    catalog_version INTEGER,
    bundle_version INTEGER,
    supplied_target_user_id UUID NOT NULL,
    supplied_target_grant_id UUID,
    target_verification VARCHAR(64) NOT NULL,
    target_user_id UUID,
    target_grant_id UUID,
    initiator_user_id UUID,
    operation_id UUID,
    action VARCHAR(64) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    projection VARCHAR(64),
    reason VARCHAR(64),
    outcome VARCHAR(64) NOT NULL,
    before_state VARCHAR(64),
    before_revision BIGINT,
    after_state VARCHAR(64),
    after_revision BIGINT,
    linked_event_id UUID,
    CONSTRAINT platform_access_audit_events_pk PRIMARY KEY (id),
    CONSTRAINT platform_access_audit_events_actor_fk FOREIGN KEY (actor_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_actor_grant_fk FOREIGN KEY (actor_grant_id, actor_user_id)
        REFERENCES public.platform_access_grants (id, recipient_user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_target_user_fk FOREIGN KEY (target_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_target_grant_fk FOREIGN KEY (target_grant_id, target_user_id)
        REFERENCES public.platform_access_grants (id, recipient_user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_initiator_fk FOREIGN KEY (initiator_user_id)
        REFERENCES public.users (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_link_fk FOREIGN KEY (linked_event_id)
        REFERENCES public.platform_access_audit_events (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_audit_events_version CHECK (event_version = 1),
    CONSTRAINT platform_access_audit_events_finite_time CHECK (isfinite(event_at)),
    CONSTRAINT platform_access_audit_events_actor_shape CHECK (
        (actor_grant_id IS NULL AND actor_grant_revision IS NULL AND actor_role IS NULL
            AND catalog_version IS NULL AND bundle_version IS NULL)
        OR (actor_user_id IS NOT NULL AND actor_grant_id IS NOT NULL AND actor_grant_revision IS NOT NULL
            AND actor_grant_revision > 0 AND actor_role IS NOT NULL
            AND actor_role IN ('PLATFORM_OWNER', 'SUPPORT_READ')
            AND catalog_version IS NOT NULL AND catalog_version = 1
            AND bundle_version IS NOT NULL AND bundle_version = 1)),
    CONSTRAINT platform_access_audit_events_target_shape CHECK (
        (target_verification = 'SUPPLIED' AND target_user_id IS NULL AND target_grant_id IS NULL)
        OR (target_verification = 'VERIFIED' AND target_user_id IS NOT NULL
            AND target_user_id = supplied_target_user_id
            AND target_grant_id IS NOT DISTINCT FROM supplied_target_grant_id)),
    CONSTRAINT platform_access_audit_events_operation_shape CHECK (
        (initiator_user_id IS NULL AND operation_id IS NULL)
        OR (initiator_user_id IS NOT NULL AND operation_id IS NOT NULL)),
    CONSTRAINT platform_access_audit_events_action_shape CHECK (
        (action = 'USER_SECURITY_READ' AND permission = 'USER_SECURITY_READ'
            AND supplied_target_grant_id IS NULL)
        OR (action = 'SUPPORT_REVOKE' AND supplied_target_grant_id IS NOT NULL
            AND permission IN ('STAFF_GRANT_SUSPEND_REVOKE', 'STAFF_GRANTS_READ'))),
    CONSTRAINT platform_access_audit_events_success_shape CHECK (
        event_kind IN ('ACCESS_DENIED', 'OPERATION_FAILED')
        OR (actor_user_id IS NOT NULL AND actor_grant_id IS NOT NULL
            AND target_verification = 'VERIFIED' AND initiator_user_id IS NOT NULL)),
    CONSTRAINT platform_access_audit_events_event_shape CHECK (
        (event_kind = 'INTENT_RECORDED' AND outcome = 'REGISTERED' AND linked_event_id IS NULL
            AND actor_user_id = initiator_user_id AND (
                (action = 'USER_SECURITY_READ' AND projection = 'USER_SECURITY' AND projection IS NOT NULL
                    AND reason IS NOT NULL AND reason IN ('USER_REQUESTED_SUPPORT', 'SECURITY_REVIEW'))
                OR (action = 'SUPPORT_REVOKE' AND permission = 'STAFF_GRANT_SUSPEND_REVOKE'
                    AND projection IS NULL AND reason IS NULL)))
        OR (event_kind = 'READ_ADMITTED' AND outcome = 'ADMITTED' AND projection IS NOT NULL
            AND reason IS NOT NULL AND (
                (action = 'USER_SECURITY_READ' AND projection IN ('USER_SECURITY', 'OPERATION_RECEIPT')
                    AND reason IN ('USER_REQUESTED_SUPPORT', 'SECURITY_REVIEW'))
                OR (action = 'SUPPORT_REVOKE' AND permission = 'STAFF_GRANTS_READ'
                    AND projection = 'OPERATION_RECEIPT' AND reason = 'ACCESS_REVIEW')))
        OR (event_kind = 'GRANT_REVOKED' AND outcome = 'COMMITTED' AND action = 'SUPPORT_REVOKE'
            AND permission = 'STAFF_GRANT_SUSPEND_REVOKE' AND projection IS NULL AND reason IS NULL
            AND actor_user_id = initiator_user_id AND linked_event_id IS NULL)
        OR (event_kind = 'ACCESS_DENIED' AND outcome = 'DENIED' AND projection IS NULL
            AND linked_event_id IS NULL AND reason IS NOT NULL AND reason IN (
                'MISSING_FACTS', 'INCONSISTENT_FACTS', 'UNKNOWN_VERSION', 'INACTIVE_ACTOR',
                'INVALID_GRANT', 'SLOT_MISMATCH', 'INVALID_SCOPE', 'INVALID_VALIDITY',
                'NOT_YET_VALID', 'EXPIRED', 'PERMISSION_DENIED', 'ACTION_MISMATCH',
                'RESOURCE_MISMATCH', 'REASON_DENIED', 'ASSURANCE_REQUIRED', 'STAMP_MISMATCH',
                'TIME_INVALID', 'ASSURANCE_EXPIRED', 'STEP_UP_REQUIRED', 'STEP_UP_EXPIRED',
                'SELF_TARGET', 'OWNER_TARGET', 'INACTIVE_TARGET', 'OCCUPIED_SLOT',
                'STATE_DENIED', 'STALE_REVISION', 'NO_CHANGE', 'REVISION_OVERFLOW',
                'INVITATION_MISMATCH', 'INTENT_ABSENT', 'INTENT_MISMATCH',
                'INTENT_UNRESOLVED', 'HISTORICAL_INITIATOR_UNAVAILABLE', 'ID_UNAVAILABLE',
                'INVALID_INPUT', 'TERMINAL_RECEIPT_REQUIRED'))
        OR (event_kind = 'OPERATION_FAILED' AND outcome = 'CONFIRMED_FAILED' AND projection IS NULL
            AND linked_event_id IS NULL AND reason IS NOT NULL AND reason IN (
                'AUDIT_UNAVAILABLE', 'DATABASE_UNAVAILABLE', 'DEADLINE_EXCEEDED',
                'INTERRUPTED', 'UNEXPECTED_FAILURE'))),
    CONSTRAINT platform_access_audit_events_revision_shape CHECK (
        (event_kind <> 'GRANT_REVOKED' AND before_state IS NULL AND before_revision IS NULL
            AND after_state IS NULL AND after_revision IS NULL)
        OR (event_kind = 'GRANT_REVOKED' AND before_state IS NOT NULL
            AND before_state IN ('ACTIVE', 'SUSPENDED') AND before_revision IS NOT NULL
            AND before_revision > 0 AND before_revision < 9223372036854775807
            AND after_state IS NOT NULL AND after_state = 'REVOKED'
            AND after_revision IS NOT NULL AND after_revision = before_revision::numeric + 1)),
    CONSTRAINT platform_access_audit_events_link_shape CHECK (
        linked_event_id IS NULL OR (event_kind = 'READ_ADMITTED'
            AND projection = 'OPERATION_RECEIPT' AND linked_event_id <> id))
);

CREATE UNIQUE INDEX platform_access_audit_events_one_registration
    ON public.platform_access_audit_events (initiator_user_id, operation_id)
    WHERE event_kind = 'INTENT_RECORDED';

CREATE TABLE public.platform_access_operation_outcomes (
    initiator_user_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    outcome_kind VARCHAR(64) NOT NULL,
    admitted_at TIMESTAMPTZ(6) NOT NULL,
    actor_grant_id UUID NOT NULL,
    actor_grant_revision BIGINT NOT NULL,
    audit_event_id UUID NOT NULL,
    before_state VARCHAR(64),
    before_revision BIGINT,
    after_state VARCHAR(64),
    after_revision BIGINT,
    CONSTRAINT platform_access_operation_outcomes_pk PRIMARY KEY (initiator_user_id, operation_id),
    CONSTRAINT platform_access_operation_outcomes_intent_fk FOREIGN KEY (initiator_user_id, operation_id)
        REFERENCES public.platform_access_operation_intents (initiator_user_id, operation_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT platform_access_operation_outcomes_actor_fk FOREIGN KEY (actor_grant_id, initiator_user_id)
        REFERENCES public.platform_access_grants (id, recipient_user_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT platform_access_operation_outcomes_audit_fk FOREIGN KEY (audit_event_id)
        REFERENCES public.platform_access_audit_events (id) ON UPDATE RESTRICT ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT platform_access_operation_outcomes_audit_unique UNIQUE (audit_event_id),
    CONSTRAINT platform_access_operation_outcomes_actor_revision CHECK (actor_grant_revision > 0),
    CONSTRAINT platform_access_operation_outcomes_finite_time CHECK (isfinite(admitted_at)),
    CONSTRAINT platform_access_operation_outcomes_shape CHECK (
        (outcome_kind = 'READ_ADMITTED' AND before_state IS NULL AND before_revision IS NULL
            AND after_state IS NULL AND after_revision IS NULL)
        OR (outcome_kind = 'REVOKED' AND before_state IS NOT NULL AND before_state IN ('ACTIVE', 'SUSPENDED')
            AND before_revision IS NOT NULL AND before_revision > 0 AND before_revision < 9223372036854775807
            AND after_state IS NOT NULL AND after_state = 'REVOKED' AND after_revision IS NOT NULL
            AND after_revision = before_revision::numeric + 1))
);

CREATE FUNCTION public.platform_access_require_read_committed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_WRITE_ISOLATION_V1: platform access write requires READ COMMITTED',
            SCHEMA = TG_TABLE_SCHEMA, TABLE = TG_TABLE_NAME,
            DETAIL = 'operation=' || TG_OP || '; actual_isolation=' || pg_catalog.current_setting('transaction_isolation');
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION public.platform_access_reject_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE = '23514',
        CONSTRAINT = 'platform_access_history_immutable',
        MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_HISTORY_V1: retained history cannot be changed',
        SCHEMA = TG_TABLE_SCHEMA, TABLE = TG_TABLE_NAME;
END;
$$;

CREATE FUNCTION public.platform_access_grants_enforce_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state IS DISTINCT FROM 'ACTIVE' OR NEW.revision IS DISTINCT FROM 1::bigint THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grants_initial_state',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_GRANT_V1: initial state must be ACTIVE at revision one';
        END IF;
    ELSE
        IF ROW(NEW.id, NEW.recipient_user_id, NEW.role, NEW.catalog_version, NEW.bundle_version,
               NEW.starts_at, NEW.validity_kind, NEW.expires_at, NEW.scope_kind, NEW.scope_target_count,
               NEW.issued_by_user_id, NEW.issued_at)
           IS DISTINCT FROM
           ROW(OLD.id, OLD.recipient_user_id, OLD.role, OLD.catalog_version, OLD.bundle_version,
               OLD.starts_at, OLD.validity_kind, OLD.expires_at, OLD.scope_kind, OLD.scope_target_count,
               OLD.issued_by_user_id, OLD.issued_at) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grants_immutable_assignment',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_GRANT_V1: assignment fields are immutable';
        END IF;
        IF NOT ((OLD.state = 'ACTIVE' AND NEW.state IN ('SUSPENDED', 'REVOKED'))
            OR (OLD.state = 'SUSPENDED' AND NEW.state IN ('ACTIVE', 'REVOKED')))
            OR NEW.state IS NULL THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grants_transition',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_GRANT_V1: unsupported lifecycle transition';
        END IF;
        IF OLD.revision = 9223372036854775807
            OR NEW.revision IS DISTINCT FROM OLD.revision::numeric + 1 THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grants_revision_step',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_GRANT_V1: exactly one checked revision step required';
        END IF;
        IF NEW.last_changed_at IS NULL
            OR NEW.last_changed_at < COALESCE(OLD.last_changed_at, OLD.issued_at) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grants_change_time',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_GRANT_V1: inconsistent change time';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER platform_access_grants_enforce_lifecycle BEFORE INSERT OR UPDATE ON public.platform_access_grants
    FOR EACH ROW EXECUTE FUNCTION public.platform_access_grants_enforce_lifecycle();

CREATE FUNCTION public.platform_access_targets_lock_parent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Lock directly in NO KEY UPDATE mode: FK key-share does not need an upgrade.
    -- Every insertion for one parent serializes before deferred counting.
    PERFORM 1 FROM public.platform_access_grants WHERE id = NEW.grant_id FOR NO KEY UPDATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER platform_access_targets_lock_parent BEFORE INSERT ON public.platform_access_grant_targets
    FOR EACH ROW EXECUTE FUNCTION public.platform_access_targets_lock_parent();

CREATE FUNCTION public.platform_access_require_exact_scope() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_id uuid;
    expected_count integer;
    actual_count bigint;
BEGIN
    IF TG_TABLE_NAME = 'platform_access_grants' THEN
        parent_id := NEW.id;
    ELSE
        parent_id := NEW.grant_id;
    END IF;
    SELECT scope_target_count INTO expected_count FROM public.platform_access_grants
        WHERE id = parent_id FOR NO KEY UPDATE;
    SELECT count(*) INTO actual_count FROM public.platform_access_grant_targets WHERE grant_id = parent_id;
    IF expected_count IS NULL OR actual_count <> expected_count THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_grant_targets_exact_count',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_SCOPE_V1: exact immutable target count required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER platform_access_grants_require_exact_scope AFTER INSERT ON public.platform_access_grants
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.platform_access_require_exact_scope();
CREATE CONSTRAINT TRIGGER platform_access_targets_require_exact_scope AFTER INSERT ON public.platform_access_grant_targets
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.platform_access_require_exact_scope();

-- Cross-record validation is deferred so insertion order does not fabricate
-- an intermediate committed result. Immutable rows make a passed link permanent.
CREATE FUNCTION public.platform_access_validate_audit_link() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    intent public.platform_access_operation_intents%ROWTYPE;
    original public.platform_access_audit_events%ROWTYPE;
    grant_role varchar(64);
BEGIN
    IF NEW.actor_grant_id IS NOT NULL THEN
        SELECT role INTO grant_role FROM public.platform_access_grants WHERE id = NEW.actor_grant_id;
        IF NEW.actor_role IS DISTINCT FROM grant_role THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_authority_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: authority reference mismatch';
        END IF;
    END IF;
    IF NEW.event_kind IN ('INTENT_RECORDED', 'READ_ADMITTED', 'GRANT_REVOKED') THEN
        SELECT * INTO intent FROM public.platform_access_operation_intents
            WHERE initiator_user_id = NEW.initiator_user_id AND operation_id = NEW.operation_id;
        IF NOT FOUND OR intent.kind IS DISTINCT FROM NEW.action
            OR intent.target_user_id IS DISTINCT FROM NEW.target_user_id
            OR intent.target_grant_id IS DISTINCT FROM NEW.target_grant_id THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_intent_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: exact intent reference required';
        END IF;
        IF NEW.event_kind = 'INTENT_RECORDED' AND (
            NEW.event_at IS DISTINCT FROM intent.registered_at
            OR NEW.actor_grant_id IS DISTINCT FROM intent.actor_grant_id
            OR NEW.actor_grant_revision IS DISTINCT FROM intent.actor_grant_revision
            OR NEW.correlation_id IS DISTINCT FROM intent.correlation_id
            OR NEW.reason IS DISTINCT FROM intent.read_reason) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_registration_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: registration audit mismatch';
        END IF;
        IF NEW.event_kind = 'READ_ADMITTED' AND NEW.projection = 'USER_SECURITY' AND (
            NEW.actor_user_id IS DISTINCT FROM intent.initiator_user_id
            OR NEW.reason IS DISTINCT FROM intent.read_reason OR NEW.linked_event_id IS NOT NULL) THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_read_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: read admission audit mismatch';
        END IF;
        IF NEW.event_kind = 'GRANT_REVOKED' AND NEW.before_revision IS DISTINCT FROM intent.expected_revision THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_revoke_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: revoke admission audit mismatch';
        END IF;
        IF NEW.linked_event_id IS NOT NULL THEN
            SELECT * INTO original FROM public.platform_access_audit_events WHERE id = NEW.linked_event_id;
            IF NOT FOUND OR original.initiator_user_id IS DISTINCT FROM NEW.initiator_user_id
                OR original.operation_id IS DISTINCT FROM NEW.operation_id
                OR original.event_kind NOT IN ('INTENT_RECORDED', 'READ_ADMITTED', 'GRANT_REVOKED')
                OR original.projection = 'OPERATION_RECEIPT' THEN
                RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_audit_events_receipt_link',
                    MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_AUDIT_V1: receipt must link exact original history';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER platform_access_audit_events_validate_link AFTER INSERT ON public.platform_access_audit_events
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.platform_access_validate_audit_link();

CREATE FUNCTION public.platform_access_require_registration_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.platform_access_audit_events
        WHERE initiator_user_id = NEW.initiator_user_id AND operation_id = NEW.operation_id
          AND event_kind = 'INTENT_RECORDED') THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_operation_intents_registration_audit',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_INTENT_V1: matching registration audit required';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER platform_access_intents_require_registration_audit
    AFTER INSERT ON public.platform_access_operation_intents DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.platform_access_require_registration_audit();

CREATE FUNCTION public.platform_access_validate_outcome() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    intent public.platform_access_operation_intents%ROWTYPE;
    audit public.platform_access_audit_events%ROWTYPE;
    target public.platform_access_grants%ROWTYPE;
BEGIN
    SELECT * INTO intent FROM public.platform_access_operation_intents
        WHERE initiator_user_id = NEW.initiator_user_id AND operation_id = NEW.operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_operation_outcomes_intent_matches',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_OUTCOME_V1: exact intent required';
    END IF;
    SELECT * INTO audit FROM public.platform_access_audit_events WHERE id = NEW.audit_event_id;
    IF NOT FOUND OR audit.initiator_user_id IS DISTINCT FROM NEW.initiator_user_id
        OR audit.operation_id IS DISTINCT FROM NEW.operation_id
        OR audit.actor_user_id IS DISTINCT FROM NEW.initiator_user_id
        OR audit.actor_grant_id IS DISTINCT FROM NEW.actor_grant_id
        OR audit.actor_grant_revision IS DISTINCT FROM NEW.actor_grant_revision
        OR audit.event_at IS DISTINCT FROM NEW.admitted_at
        OR audit.target_user_id IS DISTINCT FROM intent.target_user_id
        OR audit.target_grant_id IS DISTINCT FROM intent.target_grant_id
        OR NEW.admitted_at < intent.registered_at THEN
        RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_operation_outcomes_audit_matches',
            MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_OUTCOME_V1: exact success audit required';
    END IF;
    IF NEW.outcome_kind = 'READ_ADMITTED' THEN
        IF intent.kind <> 'USER_SECURITY_READ' OR audit.event_kind <> 'READ_ADMITTED'
            OR audit.projection IS DISTINCT FROM 'USER_SECURITY'
            OR audit.reason IS DISTINCT FROM intent.read_reason THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_operation_outcomes_read_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_OUTCOME_V1: read outcome mismatch';
        END IF;
    ELSE
        SELECT * INTO target FROM public.platform_access_grants WHERE id = intent.target_grant_id;
        IF NOT FOUND OR intent.kind <> 'SUPPORT_REVOKE' OR audit.event_kind <> 'GRANT_REVOKED'
            OR NEW.before_revision IS DISTINCT FROM intent.expected_revision
            OR ROW(NEW.before_state, NEW.before_revision, NEW.after_state, NEW.after_revision)
                IS DISTINCT FROM ROW(audit.before_state, audit.before_revision, audit.after_state, audit.after_revision)
            OR target.role <> 'SUPPORT_READ' OR target.state <> 'REVOKED'
            OR target.revision IS DISTINCT FROM NEW.after_revision
            OR target.revoked_by_user_id IS DISTINCT FROM NEW.initiator_user_id
            OR target.revoked_at IS DISTINCT FROM NEW.admitted_at THEN
            RAISE EXCEPTION USING ERRCODE = '23514', CONSTRAINT = 'platform_access_operation_outcomes_revoke_matches',
                MESSAGE = 'CREASTRIX_PLATFORM_ACCESS_OUTCOME_V1: revoke outcome mismatch';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER platform_access_outcomes_validate_link AFTER INSERT ON public.platform_access_operation_outcomes
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.platform_access_validate_outcome();

-- Separate statement guards also cover zero-row DML and CASCADE TRUNCATE.
CREATE TRIGGER platform_access_grants_require_read_committed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_access_grants
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_require_read_committed();
CREATE TRIGGER platform_access_targets_require_read_committed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_access_grant_targets
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_require_read_committed();
CREATE TRIGGER platform_access_intents_require_read_committed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_access_operation_intents
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_require_read_committed();
CREATE TRIGGER platform_access_outcomes_require_read_committed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_access_operation_outcomes
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_require_read_committed();
CREATE TRIGGER platform_access_audit_require_read_committed
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.platform_access_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_require_read_committed();

CREATE TRIGGER platform_access_grants_retain_history BEFORE DELETE OR TRUNCATE ON public.platform_access_grants
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_reject_history_mutation();
CREATE TRIGGER platform_access_targets_retain_history BEFORE UPDATE OR DELETE OR TRUNCATE ON public.platform_access_grant_targets
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_reject_history_mutation();
CREATE TRIGGER platform_access_intents_retain_history BEFORE UPDATE OR DELETE OR TRUNCATE ON public.platform_access_operation_intents
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_reject_history_mutation();
CREATE TRIGGER platform_access_outcomes_retain_history BEFORE UPDATE OR DELETE OR TRUNCATE ON public.platform_access_operation_outcomes
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_reject_history_mutation();
CREATE TRIGGER platform_access_audit_retain_history BEFORE UPDATE OR DELETE OR TRUNCATE ON public.platform_access_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_access_reject_history_mutation();
