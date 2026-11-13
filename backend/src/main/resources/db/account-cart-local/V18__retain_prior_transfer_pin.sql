-- A live pin count cannot prove that this asset never belonged to a transfer.
-- Preserve positive evidence forever. Pre-V18 rows without evidence are UNKNOWN,
-- not false: a terminal receipt may already have released its temporary rows.
LOCK TABLE account_cart.pending_asset IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE account_cart.asset ADD COLUMN ever_pinned boolean,
    ADD COLUMN reconciliation_required boolean NOT NULL DEFAULT false;
ALTER TABLE account_cart.asset ALTER COLUMN ever_pinned SET DEFAULT false;

CREATE FUNCTION account_cart.remember_pending_pin() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE account_cart.asset SET ever_pinned=true
        WHERE owner_id=NEW.owner_id AND asset_id=NEW.asset_id AND ever_pinned IS DISTINCT FROM true;
    RETURN NULL;
END $$;
CREATE TRIGGER remember_pending_pin AFTER INSERT ON account_cart.pending_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.remember_pending_pin();

CREATE FUNCTION account_cart.keep_pin_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- No physical purge is supported by this candidate. Deleting and recreating
    -- an asset row must not reset its identity, upload clock, or earlier linkage.
    IF TG_OP='DELETE' OR (OLD.ever_pinned IS DISTINCT FROM false AND NEW.ever_pinned IS false)
            OR (OLD.ever_pinned IS true AND NEW.ever_pinned IS NOT true) THEN
        RAISE EXCEPTION 'Prior asset pin history cannot be forgotten' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER keep_pin_history BEFORE UPDATE OR DELETE ON account_cart.asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.keep_pin_history();

-- Keep existing projection column order for readers upgrading in place.
CREATE OR REPLACE VIEW account_cart.asset_retention_facts AS
    SELECT a.owner_id,a.asset_id,a.kind,a.started_at,a.object_key,a.byte_count,a.sha256,a.completed,
           a.confirmed_count,a.ever_confirmed,a.last_confirmed_removed_at,
           (SELECT count(*) FROM account_cart.pending_asset p JOIN account_cart.import_receipt r
            USING(owner_id,batch_id) WHERE (p.owner_id,p.asset_id)=(a.owner_id,a.asset_id)
            AND r.state='PENDING') AS pending_count, a.ever_pinned,
           -- V16's unlink timestamp is generated in the mutating transaction;
           -- a slow commit can be later. Do not treat it as a proven seven-day
           -- post-commit clock. Hold until a future reconciler has safe timing.
           (a.reconciliation_required OR a.last_confirmed_removed_at IS NOT NULL) AS reconciliation_required
    FROM account_cart.asset a;

-- After all DDL: updating a legacy asset queues V16's deferred publication check.
UPDATE account_cart.asset a SET ever_pinned=true
    WHERE EXISTS (SELECT FROM account_cart.pending_asset p
        WHERE (p.owner_id,p.asset_id)=(a.owner_id,a.asset_id));
