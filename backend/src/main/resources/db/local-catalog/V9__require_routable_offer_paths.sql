-- A tile and its review links must resolve in the same storefront. An offer
-- cannot point to an account, checkout, or arbitrary path on the local host.
ALTER TABLE local_catalog.product ADD CONSTRAINT offer_route_is_supported CHECK (
    kind <> 'offer' OR href ~ '^/(products|collections/objects|preview/items)/[a-z0-9]+(-[a-z0-9]+)*$'
);

-- Persisted plain cart lines are identified by SKU; personalised lines use
-- a distinct serial in the same cart. Never issue one of those serials as a SKU.
ALTER TABLE local_catalog.variant ADD CONSTRAINT sku_outside_design_line_namespace
    CHECK (sku !~ '^design-[0-9]+$');
