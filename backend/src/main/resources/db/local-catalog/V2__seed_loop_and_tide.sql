-- One-time bootstrap data for the dedicated local catalog. Subsequent starts
-- preserve database edits; the normal application never loads these rows.
INSERT INTO local_catalog.product (id, name, href, personalizable, currency, design_fee_cents, display_order)
VALUES
    ('loop', 'Loop', '/products/loop', true, 'EUR', 500, 1),
    ('tide', 'Tide', '/collections/objects/tide', false, 'EUR', 0, 2);

INSERT INTO local_catalog.variant
    (product_id, id, sku, cents, previous_cents, stock, colors, check_tone, name, photos, model, display_order)
VALUES
    ('loop', 'cobalt', 'cobalt', 2400, NULL, 24, ARRAY['#244bcc'], 'light',
     '{"en":"Cobalt blue","de":"Kobaltblau","ru":"Кобальтовый синий","pl":"Kobaltowy","es":"Azul cobalto","fr":"Bleu cobalt","it":"Blu cobalto"}',
     ARRAY['/product/reference-asset-01.webp', '/product/reference-asset-03.webp', '/product/reference-asset-04.webp'],
     '/product/loop-shaped-cobalt.glb', 1),
    ('loop', 'coral', 'coral', 2600, NULL, 8, ARRAY['#e17763'], 'light',
     '{"en":"Coral","de":"Koralle","ru":"Коралловый","pl":"Koralowy","es":"Coral","fr":"Corail","it":"Corallo"}',
     ARRAY['/product/loop-coral-01.webp'], '/product/loop-shaped-coral.glb', 2),
    ('loop', 'chalk', 'chalk', 1990, 2400, 15, ARRAY['#f0eee8'], 'dark',
     '{"en":"Chalk","de":"Kreide","ru":"Меловой","pl":"Kredowy","es":"Tiza","fr":"Craie","it":"Gesso"}',
     ARRAY['/product/loop-chalk-01.webp'], '/product/loop-shaped-chalk.glb', 3),
    ('loop', 'cobalt-chalk', 'cobalt-chalk', 2800, NULL, 6, ARRAY['#244bcc','#f0eee8'], 'dark',
     '{"en":"Cobalt & chalk","de":"Kobaltblau & Kreide","ru":"Кобальтовый и меловой","pl":"Kobaltowy i kredowy","es":"Cobalto y tiza","fr":"Cobalt et craie","it":"Cobalto e gesso"}',
     ARRAY['/product/loop-cobalt-chalk-01.webp'], '/product/loop-shaped-cobalt-chalk.glb', 4),
    ('tide', 'cobalt', 'tide-cobalt', 1800, NULL, 18, ARRAY['#244bcc'], 'light',
     '{"en":"Cobalt blue","de":"Kobaltblau","ru":"Кобальтовый синий","pl":"Kobaltowy","es":"Azul cobalto","fr":"Bleu cobalt","it":"Blu cobalto"}',
     ARRAY['/shop-edition/tide.webp'], NULL, 1);
