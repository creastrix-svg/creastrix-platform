-- Only this opt-in synthetic catalog can enable an existing SKU for a direct,
-- plain addition. Absence of a row means the storefront must remain read-only.
CREATE TABLE account_cart.direct_add_variant (
    sku text PRIMARY KEY REFERENCES account_cart.variant(sku)
);

-- Same intent and same request acknowledge the original committed version.
-- This is a shopping action, not a guest-import receipt or a stock reservation.
CREATE TABLE account_cart.direct_add_receipt (
    intent_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES account_cart.cart(owner_id),
    sku text NOT NULL REFERENCES account_cart.variant(sku),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
    applied_version bigint NOT NULL CHECK (applied_version > 0),
    UNIQUE(owner_id,applied_version)
);
CREATE TRIGGER retain_direct_add BEFORE UPDATE OR DELETE ON account_cart.direct_add_receipt
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER no_bulk_forget BEFORE TRUNCATE ON account_cart.direct_add_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();
