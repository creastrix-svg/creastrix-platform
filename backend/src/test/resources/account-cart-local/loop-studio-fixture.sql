-- Explicit merchandising fixture; never run against a real catalog or overwrite merchant edits.
BEGIN;
DO $loop$
DECLARE studio uuid := '20000000-0000-4000-8000-000000000001';
BEGIN
    IF current_database() <> 'creastrix_account_cart_local' OR current_user <> 'account_cart_local_app'
        OR NOT EXISTS (SELECT FROM account_cart_fixture.bootstrap WHERE version=1) THEN
        RAISE EXCEPTION 'Requires the seeded local synthetic catalog';
    END IF;
    CREATE TABLE IF NOT EXISTS account_cart_fixture.loop_studio_bootstrap
        (version integer PRIMARY KEY CHECK (version=1));
    LOCK TABLE account_cart_fixture.loop_studio_bootstrap IN EXCLUSIVE MODE;
    IF EXISTS (SELECT FROM account_cart_fixture.loop_studio_bootstrap WHERE version=1) THEN RETURN; END IF;
    INSERT INTO account_cart.offer(id,workspace_id,label,studio_fee_cents) VALUES ('loop',studio,'Loop',500);
    INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch) VALUES
        ('loop','cobalt','cobalt','Cobalt blue',2400,24,'#244bcc'),
        ('loop','coral','coral','Coral',2600,8,'#e17763'),
        ('loop','chalk','chalk','Chalk',1990,15,'#f0eee8'),
        ('loop','cobalt-chalk','cobalt-chalk','Cobalt & chalk',2800,6,'#244bcc');
    INSERT INTO account_cart.variant_presentation(offer_id,variant_id,photos,model,second_swatch,check_tone,previous_cents) VALUES
        ('loop','cobalt',ARRAY['/product/reference-asset-01.webp','/product/reference-asset-03.webp','/product/reference-asset-04.webp'],'/product/loop-shaped-cobalt.glb',NULL,'light',NULL),
        ('loop','coral',ARRAY['/product/loop-coral-01.webp'],'/product/loop-shaped-coral.glb',NULL,'light',NULL),
        ('loop','chalk',ARRAY['/product/loop-chalk-01.webp'],'/product/loop-shaped-chalk.glb',NULL,'dark',2400),
        ('loop','cobalt-chalk',ARRAY['/product/loop-cobalt-chalk-01.webp'],'/product/loop-shaped-cobalt-chalk.glb','#f0eee8','dark',NULL);
    INSERT INTO account_cart.template_revision(offer_id,revision,configuration)
        VALUES ('loop',2,'{"format":"loop-studio-v1","variants":["cobalt","coral","chalk","cobalt-chalk"],"zones":[{"id":"surface","operation":"print","tools":["text","image"]}]}'::jsonb);
    INSERT INTO account_cart_fixture.loop_studio_bootstrap(version) VALUES (1);
END $loop$;
COMMIT;
