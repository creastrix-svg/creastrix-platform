-- Designs share catalog identities and navigation with offers, but they have
-- no sale currency, purchasable variants, stock or personalisation fee.
ALTER TABLE local_catalog.product
    ADD COLUMN kind text NOT NULL DEFAULT 'offer' CHECK (kind IN ('offer', 'edition', 'concept')),
    ALTER COLUMN currency DROP NOT NULL,
    ADD CHECK ((kind = 'offer') = (currency IS NOT NULL)),
    ADD CHECK (kind = 'offer' OR (NOT personalizable AND design_fee_cents = 0)),
    ADD UNIQUE (id, kind);

ALTER TABLE local_catalog.variant
    ADD COLUMN offer_kind text GENERATED ALWAYS AS ('offer'::text) STORED,
    ADD FOREIGN KEY (product_id, offer_kind) REFERENCES local_catalog.product (id, kind);

-- Read-side summaries only, with no implied review writer or attribution.
-- An unrated item has a count of zero and no average, not a zero-star rating.
ALTER TABLE local_catalog.product
    ADD COLUMN rating_count integer NOT NULL DEFAULT 0,
    ADD COLUMN rating_average numeric(2,1),
    ADD CHECK ((rating_count = 0 AND rating_average IS NULL) OR
               (rating_count > 0 AND rating_average IS NOT NULL AND rating_average BETWEEN 1 AND 5));

CREATE FUNCTION local_catalog.localized_text_is_valid(translations jsonb, max_length integer, allow_empty boolean)
RETURNS boolean LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(translations) <> 'object' THEN false ELSE
        translations ?& ARRAY['de','en','es','fr','it','pl','ru']
        AND NOT EXISTS (
            SELECT 1 FROM jsonb_each(translations) AS translation
            WHERE jsonb_typeof(translation.value) <> 'string'
               OR length(translation.value #>> '{}') > max_length
               OR (NOT allow_empty AND length(btrim(translation.value #>> '{}')) = 0)
        )
    END
$$;

CREATE TABLE local_catalog.item_presentation (
    product_id text PRIMARY KEY REFERENCES local_catalog.product (id),
    title jsonb NOT NULL CHECK (local_catalog.localized_text_is_valid(title, 200, false)),
    material jsonb NOT NULL CHECK (local_catalog.localized_text_is_valid(material, 200, true)),
    description jsonb NOT NULL CHECK (local_catalog.localized_text_is_valid(description, 4000, true)),
    -- NULL means the offer's primary variant supplies its current media. The
    -- designs use local asset paths; neither field grants a new media route.
    photo text CHECK (photo ~ '^/(product|shop-edition)/[a-z0-9_-]+[.]webp$'),
    thumb text CHECK (thumb ~ '^/(product|shop-edition)/[a-z0-9_-]+[.]webp$'),
    categories text[] NOT NULL CHECK (array_ndims(categories) = 1 AND cardinality(categories) > 0
        AND local_catalog.matches_each(categories, '^(living|desk|decor|carry|kitchen|gifts)$')),
    edition_order integer UNIQUE CHECK (edition_order >= 0),
    home_order integer UNIQUE CHECK (home_order >= 0)
);

-- The ordered links reference an existing item, rather than copying its name,
-- media, or rating into the recommendation. A target can appear on both rails.
CREATE TABLE local_catalog.recommendation (
    product_id text NOT NULL REFERENCES local_catalog.product (id),
    section text NOT NULL CHECK (section IN ('studio', 'related')),
    item_id text NOT NULL REFERENCES local_catalog.item_presentation (product_id),
    position integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (product_id, section, item_id),
    UNIQUE (product_id, section, position),
    CHECK (product_id <> item_id)
);
