-- These are local development records, not published offers or evidence of
-- purchases. Keep their origin queryable for a future scoped cleanup. New
-- operator-created products are intentionally unmarked by default.
ALTER TABLE local_catalog.product
    ADD COLUMN seed_origin text CHECK (seed_origin IS NULL OR seed_origin = 'buyer-catalog-v1');

-- One-off inputs for the existing photographed objects. No review text, buyer
-- identity, order, verified-purchase flag, or review timestamp is generated.
CREATE TEMPORARY TABLE merchandise_inputs (
    id text PRIMARY KEY,
    cents integer NOT NULL,
    stock integer NOT NULL,
    swatch text NOT NULL,
    check_tone text NOT NULL,
    rating_count integer NOT NULL,
    rating_average numeric(2,1)
) ON COMMIT DROP;

INSERT INTO merchandise_inputs (id, cents, stock, swatch, check_tone, rating_count, rating_average)
VALUES
    ('halo',       8900, 12, '#b77734', 'light', 30, 4.7),
    ('arc',        3400, 16, '#c3c2bd', 'dark',  20, 4.5),
    ('folio',      1600, 40, '#3c5145', 'light', 40, 4.6),
    ('dune',       3200, 18, '#ad6b56', 'light', 20, 4.7),
    ('iris',       4900, 12, '#536d88', 'light', 10, 4.4),
    ('kestrel',    6400,  8, '#775140', 'light', 20, 4.8),
    ('moss',       4200, 14, '#a2ad91', 'dark',  10, 4.6),
    ('fold',       2800, 24, '#946445', 'light', 20, 4.5),
    ('prism',      2300, 15, '#827488', 'light', 10, 4.7),
    ('loom',       1900, 22, '#31455f', 'light', 10, 4.3),
    ('rook',       3800, 13, '#3b3936', 'light', 20, 4.8),
    ('cove',       2600, 17, '#c6b494', 'dark',  20, 4.5),
    ('flute',      3600, 14, '#d2d9d9', 'dark',  30, 4.6),
    ('dot',        2200, 20, '#9f3a3c', 'light',  0, NULL),
    ('tempo',      5400, 10, '#bc9655', 'dark',  20, 4.7),
    ('lunar',      4600, 14, '#c5c5c0', 'dark',  30, 4.8),
    ('wave',       1200, 30, '#b7b7b1', 'dark',  10, 4.4),
    ('reed',       3100,  9, '#bb9a62', 'dark',  20, 4.6),
    ('pin',        1800, 18, '#bdbfbd', 'dark',   0, NULL),
    ('bend',      14900,  5, '#8c8581', 'light', 10, 4.7),
    ('linea',      3600, 20, '#3c485b', 'light', 20, 4.5),
    ('hex',        1400, 35, '#ae8758', 'dark',  30, 4.8),
    ('trail',      1500, 22, '#ae7952', 'dark',  10, 4.6),
    ('stripe',     1350, 28, '#dec77b', 'dark',  20, 4.5),
    ('orbit',       950, 42, '#50699d', 'light', 20, 4.6),
    ('ridge',      2100, 12, '#c6bba5', 'dark',  10, 4.4),
    ('nook',       2900, 19, '#607666', 'light', 20, 4.6),
    ('coil',       1600, 24, '#e4ddcb', 'dark',  10, 4.5),
    ('pebble',      750, 44, '#e2aa97', 'dark',  10, 4.7),
    ('rail',       2400, 15, '#8c8780', 'dark',   0, NULL),
    ('drift',      2700, 12, '#785440', 'light', 20, 4.7),
    ('loop-mini',  1500, 18, '#ded9d0', 'dark',  20, 4.6),
    ('loop-wide',  2800, 11, '#c5c4bd', 'dark',  10, 4.7),
    ('loop-tray',   850, 32, '#ded9d0', 'dark',  10, 4.4),
    ('loop-tall',  3200, 10, '#c5c4bd', 'dark',  10, 4.5),
    ('loop-duo',   3400,  8, '#ded9d0', 'dark',   0, NULL),
    ('loop-stand', 1800, 14, '#c5c4bd', 'dark',  10, 4.6);

-- Mark the original offers without replacing their prices, quantities, or
-- other editable business data. Display and recommendation identities are
-- kept intact for every entry, including the old design-detail URLs.
UPDATE local_catalog.product SET seed_origin = 'buyer-catalog-v1' WHERE id IN ('loop', 'tide');

UPDATE local_catalog.product AS p
SET kind = 'offer', currency = 'EUR', seed_origin = 'buyer-catalog-v1',
    rating_average = CASE WHEN p.rating_count = 0 THEN input.rating_average ELSE p.rating_average END,
    rating_count = CASE WHEN p.rating_count = 0 THEN input.rating_count ELSE p.rating_count END
FROM merchandise_inputs AS input
WHERE p.id = input.id;

INSERT INTO local_catalog.variant
    (product_id, id, sku, cents, previous_cents, stock, colors, check_tone, name, photos, model, display_order)
SELECT input.id, 'standard', input.id || '-standard', input.cents, NULL, input.stock,
       ARRAY[input.swatch], input.check_tone,
       '{"de":"Original","en":"Original","es":"Original","fr":"Original","it":"Originale","pl":"Oryginalny","ru":"Оригинальный"}'::jsonb,
       CASE WHEN card.photo IS NULL THEN ARRAY[]::text[] ELSE ARRAY[card.photo] END,
       NULL, 1
FROM merchandise_inputs AS input
JOIN local_catalog.item_presentation AS card ON card.product_id = input.id;

-- The family designs previously carried a generic "not available to buy"
-- disclosure. Use their existing translated item captions instead, without
-- inventing a material, manufacturing method or measurements.
UPDATE local_catalog.item_presentation
SET description = title
WHERE product_id IN ('loop-mini','loop-wide','loop-tray','loop-tall','loop-duo','loop-stand');
