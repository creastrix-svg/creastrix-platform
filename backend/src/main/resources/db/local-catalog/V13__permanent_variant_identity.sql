-- A buyer can keep an old product page or guest cart before making the first
-- account purchase. A SKU must retain its original meaning even if no cart has
-- recorded it yet. Stable identity is separate from price, finish name and stock.
ALTER TABLE local_catalog.product ADD CONSTRAINT product_id_fits_cart_protocol CHECK (length(id) <= 200);
ALTER TABLE local_catalog.variant ADD CONSTRAINT variant_id_fits_cart_protocol CHECK (length(id) <= 200);

CREATE TABLE local_catalog.variant_identity (
    sku text PRIMARY KEY,
    product_id text NOT NULL,
    variant_id text NOT NULL,
    UNIQUE (product_id, variant_id)
);
INSERT INTO local_catalog.variant_identity (sku,product_id,variant_id)
SELECT sku,product_id,id FROM local_catalog.variant;

CREATE FUNCTION local_catalog.reject_identity_removal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Variant identities are permanent' USING ERRCODE='23514';
END $$;
CREATE TRIGGER keep_catalog_variant_identity BEFORE UPDATE OR DELETE ON local_catalog.variant_identity
    FOR EACH ROW EXECUTE FUNCTION local_catalog.reject_identity_removal();
CREATE TRIGGER keep_catalog_variant_identity_on_truncate BEFORE TRUNCATE ON local_catalog.variant_identity
    FOR EACH STATEMENT EXECUTE FUNCTION local_catalog.reject_identity_removal();

CREATE FUNCTION local_catalog.retain_variant_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND (OLD.sku,OLD.product_id,OLD.id) IS DISTINCT FROM (NEW.sku,NEW.product_id,NEW.id) THEN
        RAISE EXCEPTION 'Variant identity cannot be reassigned' USING ERRCODE='23514';
    END IF;
    -- When two products compete for an identity, the unique indexes serialize
    -- the claims. An exact relisting is allowed; either direction of remapping is not.
    INSERT INTO local_catalog.variant_identity (sku,product_id,variant_id)
        VALUES (NEW.sku,NEW.product_id,NEW.id) ON CONFLICT DO NOTHING;
    IF NOT EXISTS(SELECT FROM local_catalog.variant_identity
            WHERE sku=NEW.sku AND product_id=NEW.product_id AND variant_id=NEW.id) THEN
        RAISE EXCEPTION 'Variant identity cannot be reassigned' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER retain_catalog_variant_identity AFTER INSERT OR UPDATE OF sku,product_id,id ON local_catalog.variant
    FOR EACH ROW EXECUTE FUNCTION local_catalog.retain_variant_identity();
