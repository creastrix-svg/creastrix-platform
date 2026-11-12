-- A named buyer-facing family is explicit. A merchandising shelf, URL prefix,
-- seller or similarly named product is not evidence of family membership.
CREATE TABLE local_catalog.merchandise_collection (
    id text PRIMARY KEY CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120)
);
ALTER TABLE local_catalog.item_presentation
    ADD COLUMN collection_id text REFERENCES local_catalog.merchandise_collection (id),
    ADD COLUMN substitution_key text CHECK (substitution_key ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

INSERT INTO local_catalog.merchandise_collection VALUES ('loop', 'Loop');
UPDATE local_catalog.item_presentation i SET collection_id = 'loop'
FROM local_catalog.product p WHERE i.product_id = p.id AND p.seed_origin = 'buyer-catalog-v1'
  AND p.id IN ('loop', 'loop-mini', 'loop-wide', 'loop-tray', 'loop-tall', 'loop-duo', 'loop-stand');

-- Curated substitutes have the same concrete use, not merely a common room,
-- material or complementary use (a coaster is not a replacement for a cup).
UPDATE local_catalog.item_presentation i SET substitution_key = types.key
FROM (VALUES
    ('loop','plant-pot'), ('loop-mini','plant-pot'), ('loop-wide','plant-pot'),
    ('loop-tall','plant-pot'), ('loop-duo','plant-pot'), ('loop-tray','plant-saucer'),
    ('loop-stand','plant-stand'), ('tide','espresso-cup'), ('halo','table-lamp'),
    ('arc','earrings'), ('folio','notebook'), ('dune','cushion'), ('iris','sunglasses'),
    ('kestrel','bookends'), ('moss','carafe'), ('fold','cardholder'), ('prism','candlestick'),
    ('loom','pencil-case'), ('rook','pepper-mill'), ('cove','tote'), ('flute','flower-vase'),
    ('dot','letter-tray'), ('tempo','desk-clock'), ('lunar','necklace'), ('wave','butter-knife'),
    ('reed','basket'), ('pin','brooch'), ('bend','side-table'), ('linea','scarf'),
    ('hex','coasters'), ('trail','luggage-tag'), ('stripe','tea-towel'), ('orbit','keyring'),
    ('ridge','incense-holder'), ('nook','laptop-sleeve'), ('coil','candle'),
    ('pebble','bar-soap'), ('rail','wall-hook'), ('drift','valet-tray')
) AS types(id,key)
JOIN local_catalog.product p ON p.id = types.id AND p.seed_origin = 'buyer-catalog-v1'
WHERE i.product_id = p.id;
