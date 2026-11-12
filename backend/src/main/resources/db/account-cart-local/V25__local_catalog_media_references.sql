-- References to published previews, never the image/model bytes or buyer-owned originals.
-- This opt-in catalog can also serve offers without any media or studio template.
ALTER TABLE account_cart.offer ADD COLUMN studio_fee_cents integer NOT NULL DEFAULT 0
    CHECK (studio_fee_cents BETWEEN 0 AND 1000000);
CREATE TABLE account_cart.variant_presentation (
    offer_id text NOT NULL, variant_id text NOT NULL,
    photos text[] NOT NULL DEFAULT '{}' CHECK (cardinality(photos) <= 10),
    model text, second_swatch text CHECK (second_swatch ~ '^#[0-9a-fA-F]{6}$'),
    check_tone text NOT NULL DEFAULT 'dark' CHECK (check_tone IN ('light','dark')),
    previous_cents integer CHECK (previous_cents BETWEEN 1 AND 1000000),
    PRIMARY KEY (offer_id,variant_id),
    FOREIGN KEY (offer_id,variant_id) REFERENCES account_cart.variant(offer_id,id)
);
