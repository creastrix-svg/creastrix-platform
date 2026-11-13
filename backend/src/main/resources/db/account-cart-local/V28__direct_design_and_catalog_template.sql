-- Earlier decisions remain immutable; only a successful personalised click remembers
-- its representative line. The line can later be removed, so this is a receipt, not a pin.
ALTER TABLE account_cart.direct_add_receipt
    DROP CONSTRAINT direct_decision_requested_sku,
    ADD CONSTRAINT direct_decision_requested_sku CHECK
        (length(sku) BETWEEN 1 AND 200 AND sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    ADD COLUMN design_id uuid,
    ADD COLUMN design_revision bigint,
    ADD COLUMN credited_line_id uuid,
    ADD CONSTRAINT direct_design_pair CHECK
        ((design_id IS NULL) = (design_revision IS NULL) AND design_revision > 0),
    ADD CONSTRAINT direct_design_credit CHECK
        ((credited_line_id IS NOT NULL) = (state='APPLIED' AND design_id IS NOT NULL));

-- Only this provenance authorises carrying a withdrawn catalog variant in a guest
-- transfer; old isolated fixtures and unresolved browser SKUs are not catalog evidence.
CREATE TABLE account_cart.storefront_variant (
    sku text PRIMARY KEY REFERENCES account_cart.variant(sku)
);

-- The private studio can render one versioned surface format, not every template.
-- The historical format name is not a restriction on which offer may declare it.
CREATE FUNCTION account_cart.studio_v2(config jsonb, rev bigint, variant_id text)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE WHEN rev=2 AND jsonb_typeof(config->'variants')='array' THEN
        config - 'variants' = '{"format":"loop-studio-v1","zones":[{"id":"surface","operation":"print","tools":["text","image"]}]}'::jsonb
        AND (config->'variants') @> jsonb_build_array(variant_id)
        AND NOT EXISTS (SELECT FROM jsonb_array_elements(config->'variants') member
            WHERE jsonb_typeof(member)<>'string' OR (member #>> '{}') !~ '^[a-z0-9]+(-[a-z0-9]+)*$')
        AND (SELECT count(*)=count(DISTINCT member) FROM jsonb_array_elements(config->'variants') member)
    ELSE false END
$$;
