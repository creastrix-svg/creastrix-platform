-- A template is data attached to an offer, not an entitlement conferred by a product name.
-- Retain the exact version used by an applied design even after the current pointer changes.
CREATE TABLE local_catalog.product_studio_template (
    product_id text NOT NULL REFERENCES local_catalog.product(id),
    revision bigint NOT NULL CHECK (revision>0),
    configuration jsonb NOT NULL CHECK (jsonb_typeof(configuration)='object'),
    PRIMARY KEY(product_id,revision)
);
ALTER TABLE local_catalog.product ADD COLUMN active_studio_revision bigint,
    ADD FOREIGN KEY (id,active_studio_revision) REFERENCES local_catalog.product_studio_template;
CREATE FUNCTION local_catalog.immutable_studio_template() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Catalog template is immutable' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_studio_template BEFORE UPDATE OR DELETE ON local_catalog.product_studio_template
    FOR EACH ROW EXECUTE FUNCTION local_catalog.immutable_studio_template();
CREATE TRIGGER no_bulk_studio_template BEFORE TRUNCATE ON local_catalog.product_studio_template
    FOR EACH STATEMENT EXECUTE FUNCTION local_catalog.immutable_studio_template();

INSERT INTO local_catalog.product_studio_template(product_id,revision,configuration)
SELECT id,2,'{"format":"loop-studio-v1","variants":["cobalt","coral","chalk","cobalt-chalk"],
       "zones":[{"id":"surface","operation":"print","tools":["text","image"]}]}'::jsonb
FROM local_catalog.product WHERE id='loop' AND kind='offer' AND personalizable;
UPDATE local_catalog.product SET active_studio_revision=2
WHERE id IN (SELECT product_id FROM local_catalog.product_studio_template WHERE revision=2);

CREATE FUNCTION local_catalog.supported_surface(config jsonb, rev bigint, variant_id text)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE WHEN rev=2 AND jsonb_typeof(config->'variants')='array' THEN
        config - 'variants' = '{"format":"loop-studio-v1","zones":[{"id":"surface","operation":"print","tools":["text","image"]}]}'::jsonb
        AND (config->'variants') @> jsonb_build_array(variant_id)
        AND NOT EXISTS (SELECT FROM jsonb_array_elements(config->'variants') member
            WHERE jsonb_typeof(member)<>'string' OR (member #>> '{}') !~ '^[a-z0-9]+(-[a-z0-9]+)*$')
        AND (SELECT count(*)=count(DISTINCT member) FROM jsonb_array_elements(config->'variants') member)
    ELSE false END
$$;

-- This is the *only* granted source for the separate local account-cart process.
-- Row-share locking inside a SECURITY DEFINER function avoids giving its login UPDATE
-- rights on seller tables. Source changes wait only through the bounded cart transaction.
-- No user identifiers, private originals, uploads, file keys, or credentials cross this view.
CREATE FUNCTION local_catalog.cart_variants(requested text[])
RETURNS TABLE(offer_id text, variant_id text, sku text, offer_label text, variant_label text,
              swatch text, second_swatch text, href text, stock integer, template_revision bigint, configuration jsonb,
              retained_revision bigint, retained_configuration jsonb)
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = pg_catalog AS $$
    SELECT p.id,v.id,v.sku,p.name,v.name->>'en',v.colors[array_lower(v.colors,1)],v.colors[array_lower(v.colors,1)+1],
           p.href,v.stock,t.revision,t.configuration,r.revision,r.configuration
      FROM local_catalog.product p
      JOIN local_catalog.variant v ON v.product_id=p.id
      JOIN local_catalog.item_presentation i ON i.product_id=p.id
      LEFT JOIN local_catalog.product_studio_template t ON t.product_id=p.id AND p.personalizable
           AND p.active_studio_revision=t.revision AND local_catalog.supported_surface(t.configuration,t.revision,v.id)
      LEFT JOIN local_catalog.product_studio_template r ON r.product_id=p.id AND r.revision=2
           AND local_catalog.supported_surface(r.configuration,r.revision,v.id)
     WHERE p.kind='offer' AND v.sku=ANY(requested) AND cardinality(requested) BETWEEN 1 AND 1000
     ORDER BY p.id,v.id FOR SHARE OF p,v,i
$$;
REVOKE ALL ON FUNCTION local_catalog.cart_variants(text[]) FROM PUBLIC;
