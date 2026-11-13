-- Previous confirmed-unlink timestamps predate COMMIT and omit pending transfers.
-- Never backfill a clock for an orphan from those timestamps or from the migration date.
LOCK TABLE account_cart.design_asset, account_cart.pending_asset, account_cart.import_receipt,
           account_cart.asset IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE account_cart.asset ADD COLUMN durable_release_xid xid8,
    ADD COLUMN unreferenced_observed_at timestamptz;
-- Existing edges are known live at this migration. Newly linked and unlinked
-- in the same transaction does not by itself prove a previously committed link.
ALTER TABLE account_cart.design_asset ADD COLUMN linked_xid xid8 NOT NULL DEFAULT pg_current_xact_id();

CREATE FUNCTION account_cart.retention_edge_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        UPDATE account_cart.asset SET durable_release_xid=NULL, unreferenced_observed_at=NULL
          WHERE (owner_id,asset_id)=(NEW.owner_id,NEW.asset_id);
    ELSE
        UPDATE account_cart.asset
           SET durable_release_xid=CASE WHEN OLD.linked_xid<>pg_current_xact_id()
                                        THEN pg_current_xact_id() ELSE NULL END, unreferenced_observed_at=NULL
         WHERE (owner_id,asset_id)=(OLD.owner_id,OLD.asset_id);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER retention_design_edge AFTER INSERT OR DELETE ON account_cart.design_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.retention_edge_change();
CREATE TRIGGER retention_pending_edge AFTER INSERT ON account_cart.pending_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.retention_edge_change();

-- The receipt transition, not cleanup of its terminal pin rows, ends these
-- references. Its original immutable creation transaction also created its pins.
CREATE FUNCTION account_cart.retention_receipt_release() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE edge record;
BEGIN
    FOR edge IN SELECT p.asset_id FROM account_cart.pending_asset p
                 WHERE (p.owner_id,p.batch_id)=(OLD.owner_id,OLD.batch_id) ORDER BY p.asset_id
    LOOP
        UPDATE account_cart.asset
           SET durable_release_xid=CASE WHEN OLD.creation_xid<>pg_current_xact_id()
                                        THEN pg_current_xact_id() ELSE NULL END, unreferenced_observed_at=NULL
         WHERE (owner_id,asset_id)=(OLD.owner_id,edge.asset_id);
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER retention_terminal_receipt BEFORE UPDATE OF state ON account_cart.import_receipt
    FOR EACH ROW WHEN (OLD.state='PENDING' AND NEW.state IN ('APPLIED','RETIRED'))
    EXECUTE FUNCTION account_cart.retention_receipt_release();

-- Internal confirmation only. All changes to the reference set take a conflicting
-- asset row lock and discard the observation; read both reference sources after
-- acquiring that lock. Never start a grace clock inside the unlink transaction.
CREATE FUNCTION account_cart.observe_unreferenced(who uuid, asset_key uuid) RETURNS timestamptz
LANGUAGE plpgsql VOLATILE AS $$
DECLARE a account_cart.asset%ROWTYPE;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN RETURN NULL; END IF;
    SELECT * INTO a FROM account_cart.asset WHERE (owner_id,asset_id)=(who,asset_key)
        FOR NO KEY UPDATE SKIP LOCKED;
    IF NOT FOUND OR a.reconciliation_required OR a.ever_pinned IS NULL OR a.durable_release_xid IS NULL
       OR a.durable_release_xid=pg_current_xact_id() OR a.confirmed_count<>0
       OR NOT (a.ever_confirmed OR a.ever_pinned)
       OR EXISTS (SELECT FROM account_cart.design_asset d WHERE (d.owner_id,d.asset_id)=(who,asset_key))
       OR EXISTS (SELECT FROM account_cart.pending_asset p JOIN account_cart.import_receipt r USING(owner_id,batch_id)
                   WHERE (p.owner_id,p.asset_id)=(who,asset_key) AND r.state='PENDING')
       THEN RETURN NULL;
    END IF;
    IF a.unreferenced_observed_at IS NULL THEN
        UPDATE account_cart.asset SET unreferenced_observed_at=clock_timestamp()
         WHERE (owner_id,asset_id)=(who,asset_key) RETURNING unreferenced_observed_at INTO a.unreferenced_observed_at;
    END IF;
    RETURN a.unreferenced_observed_at;
END $$;

-- Preserve V18's conservative projection for old readers. The new reader uses
-- the separately proved clock, never last_confirmed_removed_at.
CREATE VIEW account_cart.durable_asset_retention_facts AS
    SELECT f.owner_id,f.asset_id,f.started_at,f.completed,f.ever_confirmed,f.ever_pinned,
           f.confirmed_count,f.pending_count,a.unreferenced_observed_at,a.reconciliation_required
      FROM account_cart.asset_retention_facts f JOIN account_cart.asset a USING(owner_id,asset_id);
