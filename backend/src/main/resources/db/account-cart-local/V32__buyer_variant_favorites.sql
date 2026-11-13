-- Isolated buyer writer only. The separate catalog's V13 registry permanently
-- binds a published SKU to one (offer, variant); no seller stock is reserved.
CREATE TABLE account_cart.favorite_shelf (
    owner_id uuid PRIMARY KEY REFERENCES public.users,
    version bigint NOT NULL CHECK (version >= 0)
);
CREATE TABLE account_cart.favorite_variant (
    owner_id uuid NOT NULL REFERENCES account_cart.favorite_shelf,
    sku text NOT NULL CHECK (length(sku) BETWEEN 1 AND 200 AND sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    PRIMARY KEY (owner_id, sku)
);
-- No cart row, design, price or other buyer is part of a favorite's identity.
-- Retain successful no-ops and definitive refusals too: a lost response must
-- never let a repeated request make a different decision after a later unlike.
CREATE TABLE account_cart.favorite_receipt (
    owner_id uuid NOT NULL REFERENCES public.users,
    intent_id uuid NOT NULL,
    operation text NOT NULL CHECK (operation IN ('SET','UNSET','IMPORT')),
    guest_intent uuid REFERENCES account_cart.guest_login_intent(intent_id),
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash) = 32),
    applied boolean NOT NULL,
    CHECK ((guest_intent IS NOT NULL) = (operation = 'IMPORT')),
    CHECK (guest_intent IS NULL OR guest_intent = intent_id),
    UNIQUE (guest_intent),
    PRIMARY KEY (owner_id, intent_id)
);
CREATE TRIGGER favorite_receipt_immutable BEFORE UPDATE OR DELETE ON account_cart.favorite_receipt
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER favorite_receipt_no_truncate BEFORE TRUNCATE ON account_cart.favorite_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();
