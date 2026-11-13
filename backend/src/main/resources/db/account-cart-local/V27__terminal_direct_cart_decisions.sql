-- Existing V26 receipts are applied outcomes. A valid click that the catalog
-- declines also needs a permanent identity: a delayed copy must not become a new
-- purchase after a stock increase, listing activation or new SKU registration.
ALTER TABLE account_cart.direct_add_receipt
    DROP CONSTRAINT direct_add_receipt_owner_id_fkey,
    DROP CONSTRAINT direct_add_receipt_sku_fkey,
    ALTER COLUMN applied_version DROP NOT NULL,
    ADD COLUMN state text NOT NULL DEFAULT 'APPLIED' CHECK (state IN ('APPLIED','REJECTED')),
    ADD CONSTRAINT direct_decision_owner FOREIGN KEY (owner_id) REFERENCES public.users(id),
    ADD CONSTRAINT direct_decision_requested_sku CHECK
        (length(sku) BETWEEN 1 AND 128 AND sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

-- A refusal need not have a cart or a resolved SKU. Preserve the original
-- referential guarantees on successful credits, including pre-existing ones.
ALTER TABLE account_cart.direct_add_receipt
    ADD COLUMN credited_owner uuid GENERATED ALWAYS AS (CASE WHEN state='APPLIED' THEN owner_id END) STORED
        REFERENCES account_cart.cart(owner_id),
    ADD COLUMN credited_sku text GENERATED ALWAYS AS (CASE WHEN state='APPLIED' THEN sku END) STORED
        REFERENCES account_cart.variant(sku),
    ADD CONSTRAINT direct_decision_version CHECK ((state='APPLIED') = (applied_version IS NOT NULL));
