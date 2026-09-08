-- V9 is transactional: the isolation check must precede every table lock.
-- Do not change the caller's isolation or retry this migration implicitly.
DO $$
BEGIN
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING
            ERRCODE = '0A000',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_ISOLATION_V1: migration requires READ COMMITTED',
            DETAIL = 'actual_isolation=' || pg_catalog.current_setting('transaction_isolation');
    END IF;
END;
$$;

-- Separate top-level commands, in this order, on the same Flyway connection.
-- The complete barrier excludes foundation writers until validation, DDL and
-- the successful history entry commit together. Never release a partial barrier.
LOCK TABLE ONLY public.organizations IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE ONLY public.organization_memberships IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE ONLY public.workspaces IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE ONLY public.workspace_memberships IN SHARE ROW EXCLUSIVE MODE;

-- A new RC command after all four locks observes writers that committed while
-- acquisition was waiting. Permanent predicates count Memberships, not User status.
DO $$
BEGIN
    IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION USING
            ERRCODE = '0A000',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_ISOLATION_V1: migration requires READ COMMITTED',
            DETAIL = 'actual_isolation=' || pg_catalog.current_setting('transaction_isolation');
    END IF;

    IF (SELECT count(*) FROM pg_catalog.pg_locks
        WHERE pid = pg_catalog.pg_backend_pid()
          AND locktype = 'relation' AND mode = 'ShareRowExclusiveLock' AND granted
          AND relation IN ('public.organizations'::regclass,
                           'public.organization_memberships'::regclass,
                           'public.workspaces'::regclass,
                           'public.workspace_memberships'::regclass)) <> 4 THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_BOUNDARY_V1: complete transaction barrier required';
    END IF;

    -- ONLY is deliberate: descendants would be a different, unapproved schema
    -- baseline. Stop rather than silently leave their direct write paths unguarded.
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_inherits
        WHERE inhparent IN ('public.organizations'::regclass,
                            'public.organization_memberships'::regclass,
                            'public.workspaces'::regclass,
                            'public.workspace_memberships'::regclass)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '55000',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_BOUNDARY_V1: foundation descendants are unsupported';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.organizations o
        WHERE NOT EXISTS (
            SELECT 1 FROM public.organization_memberships m
            WHERE m.organization_id = o.id AND m.role = 'OWNER' AND m.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1: invalid foundation data',
            DETAIL = 'predicate=organization_active_owner';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.workspaces w
        WHERE w.owner_type = 'USER' AND NOT EXISTS (
            SELECT 1 FROM public.workspace_memberships m
            WHERE m.workspace_id = w.id AND m.user_id = w.owner_user_id
              AND m.role = 'ADMIN' AND m.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1: invalid foundation data',
            DETAIL = 'predicate=user_workspace_owner_admin';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.workspaces w
        WHERE NOT EXISTS (
            SELECT 1 FROM public.workspace_memberships m
            WHERE m.workspace_id = w.id AND m.role = 'ADMIN' AND m.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1: invalid foundation data',
            DETAIL = 'predicate=workspace_active_admin';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.workspaces w
        WHERE w.owner_type = 'ORGANIZATION' AND NOT EXISTS (
            SELECT 1 FROM public.organization_memberships om
            JOIN public.workspace_memberships wm ON wm.user_id = om.user_id
            WHERE om.organization_id = w.owner_organization_id
              AND om.role = 'OWNER' AND om.status = 'ACTIVE'
              AND wm.workspace_id = w.id AND wm.role = 'ADMIN' AND wm.status = 'ACTIVE'
        )
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'CREASTRIX_FOUNDATION_MIGRATION_VALIDATION_V1: invalid foundation data',
            DETAIL = 'predicate=organization_workspace_owner_admin';
    END IF;
END;
$$;

CREATE FUNCTION public.foundation_require_read_committed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    actual_isolation text := pg_catalog.current_setting('transaction_isolation');
BEGIN
    IF actual_isolation <> 'read committed' THEN
        RAISE EXCEPTION USING
            ERRCODE = '0A000',
            MESSAGE = 'CREASTRIX_FOUNDATION_WRITE_ISOLATION_V1: foundation write requires READ COMMITTED',
            SCHEMA = TG_TABLE_SCHEMA,
            TABLE = TG_TABLE_NAME,
            DETAIL = 'operation=' || TG_OP || '; actual_isolation=' || actual_isolation;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER organizations_require_read_committed
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.organizations
FOR EACH STATEMENT EXECUTE FUNCTION public.foundation_require_read_committed();

CREATE TRIGGER organization_memberships_require_read_committed
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.organization_memberships
FOR EACH STATEMENT EXECUTE FUNCTION public.foundation_require_read_committed();

CREATE TRIGGER workspaces_require_read_committed
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.workspaces
FOR EACH STATEMENT EXECUTE FUNCTION public.foundation_require_read_committed();

CREATE TRIGGER workspace_memberships_require_read_committed
BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON public.workspace_memberships
FOR EACH STATEMENT EXECUTE FUNCTION public.foundation_require_read_committed();
