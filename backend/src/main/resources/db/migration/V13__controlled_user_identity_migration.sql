-- Forward-only technical exception for an offline, explicitly controlled owner migration.
-- V11 history and its immutable triggers stay intact. A retained pair is never an active alias.
ALTER TABLE public.user_identity_bindings DROP CONSTRAINT user_identity_bindings_user_unique;
ALTER TABLE public.user_identity_bindings ADD CONSTRAINT user_identity_bindings_owner_pair_unique
    UNIQUE (user_id, issuer, subject) NOT DEFERRABLE;

CREATE TABLE public.user_identity_migration_receipts (
    operation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    expected_generation BIGINT NOT NULL CHECK (expected_generation > 0),
    recorded_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    old_issuer TEXT COLLATE "C" NOT NULL,
    old_subject TEXT COLLATE "C" NOT NULL,
    new_issuer TEXT COLLATE "C" NOT NULL,
    new_subject TEXT COLLATE "C" NOT NULL,
    CONSTRAINT user_identity_receipts_old_fk FOREIGN KEY (user_id, old_issuer, old_subject)
        REFERENCES public.user_identity_bindings (user_id, issuer, subject) ON DELETE RESTRICT,
    CONSTRAINT user_identity_receipts_new_fk FOREIGN KEY (user_id, new_issuer, new_subject)
        REFERENCES public.user_identity_bindings (user_id, issuer, subject) ON DELETE RESTRICT,
    CONSTRAINT user_identity_receipts_result_unique UNIQUE (operation_id, user_id, new_issuer, new_subject),
    CONSTRAINT user_identity_receipts_distinct_pair CHECK ((old_issuer, old_subject) <> (new_issuer, new_subject))
);

CREATE TABLE public.user_identity_current (
    user_id UUID PRIMARY KEY,
    issuer TEXT COLLATE "C" NOT NULL,
    subject TEXT COLLATE "C" NOT NULL,
    operation_id UUID,
    CONSTRAINT user_identity_current_pair_unique UNIQUE (issuer, subject),
    CONSTRAINT user_identity_current_owner_fk FOREIGN KEY (user_id, issuer, subject)
        REFERENCES public.user_identity_bindings (user_id, issuer, subject) ON DELETE RESTRICT,
    CONSTRAINT user_identity_current_receipt_fk FOREIGN KEY (operation_id, user_id, issuer, subject)
        REFERENCES public.user_identity_migration_receipts (operation_id, user_id, new_issuer, new_subject)
        ON DELETE RESTRICT
);

-- Only actual V12 bindings are backfilled. Legacy Users without a binding get no invented identity.
INSERT INTO public.user_identity_current (user_id, issuer, subject)
    SELECT user_id, issuer, subject FROM public.user_identity_bindings;

CREATE FUNCTION public.user_identity_current_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Current identity selector is retained' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_current_retained', TABLE = 'user_identity_current', SCHEMA = 'public';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.operation_id IS NOT NULL THEN
            RAISE EXCEPTION 'Initial identity selector cannot adopt a receipt' USING ERRCODE = '23514',
                CONSTRAINT = 'user_identity_current_initial', TABLE = 'user_identity_current', SCHEMA = 'public';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
            OR (NEW.issuer, NEW.subject) IS NOT DISTINCT FROM (OLD.issuer, OLD.subject)
            OR NEW.operation_id IS NULL OR NEW.operation_id IS NOT DISTINCT FROM OLD.operation_id
            OR NOT EXISTS (
                SELECT 1 FROM public.user_identity_migration_receipts r
                WHERE r.operation_id = NEW.operation_id AND r.user_id = OLD.user_id
                    AND r.recorded_xid = pg_current_xact_id()
                    AND (r.old_issuer, r.old_subject) = (OLD.issuer, OLD.subject)
                    AND (r.new_issuer, r.new_subject) = (NEW.issuer, NEW.subject)) THEN
        RAISE EXCEPTION 'Identity switch requires its exact receipt' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_current_exact_receipt', TABLE = 'user_identity_current', SCHEMA = 'public';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_identity_current_guard BEFORE INSERT OR UPDATE OR DELETE ON public.user_identity_current
    FOR EACH ROW EXECUTE FUNCTION public.user_identity_current_guard();

CREATE FUNCTION public.user_identity_history_requires_current() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.user_identity_current c WHERE c.user_id = NEW.user_id)
            OR NOT (EXISTS (SELECT 1 FROM public.user_identity_current c
                        WHERE (c.user_id, c.issuer, c.subject) = (NEW.user_id, NEW.issuer, NEW.subject))
                OR EXISTS (SELECT 1 FROM public.user_identity_migration_receipts r WHERE r.user_id = NEW.user_id
                        AND ((r.old_issuer, r.old_subject) = (NEW.issuer, NEW.subject)
                            OR (r.new_issuer, r.new_subject) = (NEW.issuer, NEW.subject)))) THEN
        RAISE EXCEPTION 'Identity history requires an atomic current selector or migration receipt' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_history_current', TABLE = 'user_identity_bindings', SCHEMA = 'public';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER user_identity_history_requires_current AFTER INSERT ON public.user_identity_bindings
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.user_identity_history_requires_current();

CREATE FUNCTION public.user_identity_receipt_requires_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- xid8 keeps an old receipt from being adopted again after a controlled return.
    -- It is a physical-transaction witness, not authority or an external identity.
    IF NEW.recorded_xid <> pg_current_xact_id() OR NOT EXISTS (SELECT 1 FROM public.user_identity_current c
            WHERE c.user_id = NEW.user_id AND c.operation_id = NEW.operation_id
                AND (c.issuer, c.subject) = (NEW.new_issuer, NEW.new_subject)) THEN
        RAISE EXCEPTION 'Migration receipt requires its atomic selector result' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_receipt_atomic_result', TABLE = 'user_identity_migration_receipts', SCHEMA = 'public';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER user_identity_receipt_requires_result AFTER INSERT ON public.user_identity_migration_receipts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.user_identity_receipt_requires_result();

-- Reuse only V11's structural immutable/retention function, not its historical cardinality.
CREATE TRIGGER user_identity_receipts_preserve BEFORE UPDATE OR DELETE ON public.user_identity_migration_receipts
    FOR EACH ROW EXECUTE FUNCTION public.user_identity_bindings_preserve();
CREATE TRIGGER user_identity_receipts_preserve_on_truncate BEFORE TRUNCATE ON public.user_identity_migration_receipts
    FOR EACH STATEMENT EXECUTE FUNCTION public.user_identity_bindings_preserve();
CREATE TRIGGER user_identity_current_preserve_on_truncate BEFORE TRUNCATE ON public.user_identity_current
    FOR EACH STATEMENT EXECUTE FUNCTION public.user_identity_bindings_preserve();

-- Invoker privileges, no HTTP wiring and no identity-control proof supplied by SQL.
-- The runbook requires a stopped single application, explicit authorization and fresh proof of both pairs.
-- The caller owns the bounded READ COMMITTED transaction/commit. This function never commits separately.
CREATE FUNCTION public.migrate_owner_identity(
    operation UUID, expected_user UUID, generation BIGINT,
    expected_issuer TEXT, expected_subject TEXT, target_issuer TEXT, target_subject TEXT
) RETURNS TEXT LANGUAGE plpgsql AS $$
DECLARE
    account public.users%ROWTYPE;
    current_identity public.user_identity_current%ROWTYPE;
    receipt public.user_identity_migration_receipts%ROWTYPE;
    target_owner UUID;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Owner identity migration requires READ COMMITTED' USING ERRCODE = '0A000';
    END IF;
    IF operation IS NULL OR expected_user IS NULL OR generation IS NULL OR generation <= 0
            OR expected_issuer IS NULL OR expected_subject IS NULL OR target_issuer IS NULL OR target_subject IS NULL
            OR btrim(expected_issuer) = '' OR btrim(expected_subject) = ''
            OR btrim(target_issuer) = '' OR btrim(target_subject) = ''
            OR (expected_issuer, expected_subject) = (target_issuer, target_subject) THEN
        RAISE EXCEPTION 'Invalid owner identity migration payload' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_migration_payload';
    END IF;
    -- Serialize transitions for this User. No network, Servlet or external IdP work happens under this lock.
    SELECT * INTO account FROM public.users WHERE id = expected_user FOR UPDATE;
    IF NOT FOUND OR account.status <> 'ACTIVE' OR account.account_eligibility_generation <> generation
            OR (SELECT count(*) FROM public.user_profiles WHERE user_id = expected_user) <> 1 THEN
        RAISE EXCEPTION 'Owner account preflight changed' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_migration_account';
    END IF;
    SELECT * INTO receipt FROM public.user_identity_migration_receipts WHERE operation_id = operation;
    IF FOUND THEN
        IF receipt.user_id <> expected_user OR receipt.expected_generation <> generation
                OR (receipt.old_issuer, receipt.old_subject) <> (expected_issuer, expected_subject)
                OR (receipt.new_issuer, receipt.new_subject) <> (target_issuer, target_subject) THEN
            RAISE EXCEPTION 'Migration operation payload conflict' USING ERRCODE = '23514',
                CONSTRAINT = 'user_identity_migration_operation_conflict';
        END IF;
        -- A retained receipt proves the old operation, not that no later authorized switch occurred.
        RETURN 'REPLAY';
    END IF;
    SELECT * INTO current_identity FROM public.user_identity_current WHERE user_id = expected_user FOR UPDATE;
    IF NOT FOUND OR (current_identity.issuer, current_identity.subject) <> (expected_issuer, expected_subject) THEN
        RAISE EXCEPTION 'Expected current identity changed' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_migration_expected_current';
    END IF;
    SELECT user_id INTO target_owner FROM public.user_identity_bindings
        WHERE issuer = target_issuer COLLATE "C" AND subject = target_subject COLLATE "C";
    IF FOUND AND target_owner <> expected_user THEN
        RAISE EXCEPTION 'Target identity is already owned' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_migration_target_owned';
    END IF;
    IF NOT FOUND THEN
        INSERT INTO public.user_identity_bindings (issuer, subject, user_id)
            VALUES (target_issuer, target_subject, expected_user);
    END IF;
    INSERT INTO public.user_identity_migration_receipts
        (operation_id, user_id, expected_generation, old_issuer, old_subject, new_issuer, new_subject)
        VALUES (operation, expected_user, generation, expected_issuer, expected_subject, target_issuer, target_subject);
    UPDATE public.user_identity_current SET issuer = target_issuer, subject = target_subject, operation_id = operation
        WHERE user_id = expected_user AND (issuer, subject) = (expected_issuer, expected_subject);
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Expected current identity changed' USING ERRCODE = '23514',
            CONSTRAINT = 'user_identity_migration_expected_current';
    END IF;
    RETURN 'APPLIED';
END;
$$;
