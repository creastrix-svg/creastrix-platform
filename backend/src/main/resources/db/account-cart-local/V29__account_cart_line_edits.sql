-- Soft removal preserves the exact variant, revision, quantity and private file
-- references for undo. Removed lines remain visible but do not consume stock.
ALTER TABLE account_cart.cart_line ADD COLUMN removed boolean NOT NULL DEFAULT false;

-- The decision outlives the line (dismiss may have deleted it). A lost response
-- is reconciled by the same owner and complete request, never by a guessed total.
CREATE TABLE account_cart.line_edit_receipt (
    intent_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES public.users,
    line_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('quantity','remove','restore','dismiss')),
    expected_version bigint NOT NULL CHECK (expected_version>=0),
    quantity integer,
    state text NOT NULL CHECK (state IN ('APPLIED','STALE','REJECTED')),
    applied_version bigint,
    CHECK ((action='quantity') = (quantity IS NOT NULL) AND quantity BETWEEN 1 AND 1000000),
    CHECK ((state='APPLIED') = (applied_version IS NOT NULL) AND applied_version>=expected_version)
);
CREATE TRIGGER keep_edit_decision BEFORE UPDATE OR DELETE ON account_cart.line_edit_receipt
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER no_bulk_forget BEFORE TRUNCATE ON account_cart.line_edit_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();
