-- A library entry is a mutable bookmark, never the design snapshot held by a
-- cart line. A copy can point at the same snapshot without copying any bytes.
CREATE TABLE account_cart.library_entry (
    owner_id uuid NOT NULL REFERENCES public.users,
    id uuid NOT NULL,
    version bigint NOT NULL CHECK(version > 0),
    offer_id text NOT NULL, variant_id text NOT NULL,
    design_id uuid, design_revision bigint,
    caption text CHECK(char_length(caption) BETWEEN 1 AND 32),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(owner_id,id),
    CHECK ((design_id IS NULL) = (design_revision IS NULL) AND design_revision=1),
    CHECK (design_id IS NOT NULL OR caption IS NULL),
    FOREIGN KEY (offer_id,variant_id) REFERENCES account_cart.variant,
    FOREIGN KEY (owner_id,design_id,design_revision,offer_id,variant_id)
        REFERENCES account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id)
);
CREATE INDEX library_active_by_owner ON account_cart.library_entry(owner_id,updated_at DESC,id)
    WHERE design_id IS NOT NULL;
-- The tombstone does not retain source bytes. It prevents a delayed create/update
-- from reusing a deleted entry ID. Receipts do not pin the snapshot they describe.
CREATE TABLE account_cart.library_receipt (
    owner_id uuid NOT NULL REFERENCES public.users, intent_id uuid NOT NULL,
    action text NOT NULL CHECK(action IN ('save','copy','delete')),
    entry_id uuid NOT NULL, expected_version bigint NOT NULL CHECK(expected_version >= 0),
    design_id uuid, copy_id uuid, result_version bigint NOT NULL CHECK(result_version > 0),
    PRIMARY KEY(owner_id,intent_id),
    CHECK ((design_id IS NOT NULL) = (action='save')
       AND (copy_id IS NOT NULL) = (action='copy')
       AND (action='save' OR expected_version>0))
);
CREATE TRIGGER preserve_library_receipt BEFORE UPDATE OR DELETE ON account_cart.library_receipt
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER preserve_library_receipt_bulk BEFORE TRUNCATE ON account_cart.library_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();
