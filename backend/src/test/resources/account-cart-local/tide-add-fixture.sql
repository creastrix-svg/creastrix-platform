-- Install once in the synthetic lane. These initial values match local-catalog V2.
-- Each subsequent product read and cart write in this lane uses the same permanent row.
BEGIN;
DO $tide$
DECLARE studio uuid := '20000000-0000-4000-8000-000000000001';
BEGIN
    IF current_database() <> 'creastrix_account_cart_local' OR current_user <> 'account_cart_local_app'
        OR NOT EXISTS (SELECT FROM account_cart_fixture.bootstrap WHERE version=1) THEN
        RAISE EXCEPTION 'Requires the seeded local synthetic catalog';
    END IF;
    CREATE TABLE IF NOT EXISTS account_cart_fixture.tide_bootstrap
        (version integer PRIMARY KEY CHECK (version=1));
    LOCK TABLE account_cart_fixture.tide_bootstrap IN EXCLUSIVE MODE;
    IF EXISTS (SELECT FROM account_cart_fixture.tide_bootstrap WHERE version=1) THEN RETURN; END IF;
    INSERT INTO account_cart.offer(id,workspace_id,label) VALUES ('tide',studio,'Tide');
    INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
        VALUES ('tide','cobalt','tide-cobalt','Cobalt blue',1800,18,'#244bcc');
    INSERT INTO account_cart.variant_presentation(offer_id,variant_id,photos,check_tone)
        VALUES ('tide','cobalt',ARRAY['/shop-edition/tide.webp'],'light');
    INSERT INTO account_cart.direct_add_variant(sku) VALUES ('tide-cobalt');
    INSERT INTO account_cart_fixture.tide_bootstrap(version) VALUES (1);
END $tide$;
COMMIT;
