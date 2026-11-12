-- Local buyer-facing read dataset, independent of publication, stock allocation
-- and order management. An entry groups its independently priced variants.
CREATE FUNCTION local_catalog.matches_each(items text[], pattern text)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT NOT EXISTS (SELECT 1 FROM unnest(items) AS entry(v) WHERE v IS NULL OR v !~ pattern)
$$;

CREATE FUNCTION local_catalog.has_finish_names(labels jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(labels) <> 'object' THEN false ELSE
        labels ?& ARRAY['de','en','es','fr','it','pl','ru']
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(labels) e
            WHERE jsonb_typeof(e.value) <> 'string' OR length(btrim(e.value #>> '{}')) NOT BETWEEN 1 AND 120)
    END
$$;

CREATE TABLE local_catalog.product (
    id text PRIMARY KEY CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    href text NOT NULL UNIQUE CHECK (href ~ '^/[a-z0-9][a-z0-9/-]*$'),
    personalizable boolean NOT NULL,
    currency text NOT NULL CHECK (currency = 'EUR'),
    design_fee_cents integer NOT NULL CHECK (design_fee_cents BETWEEN 0 AND 1000000),
    display_order integer NOT NULL CHECK (display_order >= 0),
    CHECK (personalizable OR design_fee_cents = 0)
);

CREATE TABLE local_catalog.variant (
    product_id text NOT NULL REFERENCES local_catalog.product (id),
    id text NOT NULL CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    sku text NOT NULL UNIQUE CHECK (sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    -- Bound local display inputs, including line-total multiplication in the client.
    -- These are not production commerce limits.
    cents integer NOT NULL CHECK (cents BETWEEN 0 AND 1000000),
    previous_cents integer CHECK (previous_cents > cents AND previous_cents <= 1000000),
    stock integer NOT NULL CHECK (stock BETWEEN 0 AND 1000000),
    colors text[] NOT NULL CHECK (array_ndims(colors) = 1 AND cardinality(colors) BETWEEN 1 AND 2
        AND local_catalog.matches_each(colors, '^#[A-Fa-f0-9]{6}$')),
    check_tone text NOT NULL CHECK (check_tone IN ('light', 'dark')),
    name jsonb NOT NULL CHECK (local_catalog.has_finish_names(name)),
    photos text[] NOT NULL CHECK (cardinality(photos) <= 10 AND coalesce(array_ndims(photos),1) = 1
        AND local_catalog.matches_each(photos, '^/(product|shop-edition)/[a-z0-9_-]+[.]webp$')),
    model text CHECK (model ~ '^/(product|shop-edition)/[a-z0-9_-]+[.]glb$'),
    display_order integer NOT NULL CHECK (display_order >= 0),
    PRIMARY KEY (product_id, id)
);
