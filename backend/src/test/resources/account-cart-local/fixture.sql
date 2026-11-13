-- Explicit synthetic bootstrap, not a Flyway migration. Seed the real local schema once;
-- subsequent starts neither overwrite edits nor recreate deleted cart lines. Run only
-- after verifying this private fixture's stored file through LocalCartFixtureAsset.
BEGIN;
DO $guard$
BEGIN
    IF current_database() <> 'creastrix_account_cart_local' OR current_user <> 'account_cart_local_app' THEN
        RAISE EXCEPTION 'Not the dedicated synthetic account-cart database';
    END IF;
END $guard$;
CREATE SCHEMA IF NOT EXISTS account_cart_fixture;
CREATE TABLE IF NOT EXISTS account_cart_fixture.bootstrap (version int PRIMARY KEY CHECK (version = 1));
LOCK TABLE account_cart_fixture.bootstrap IN EXCLUSIVE MODE;
DO $seed$
DECLARE
    buyer uuid := '10000000-0000-4000-8000-000000000001';
    maker uuid := '10000000-0000-4000-8000-000000000002';
    workspace uuid := '20000000-0000-4000-8000-000000000001';
    design uuid := '30000000-0000-4000-8000-000000000001';
    asset uuid := '40000000-0000-4000-8000-000000000001';
BEGIN
    IF EXISTS (SELECT FROM account_cart_fixture.bootstrap) THEN RETURN; END IF;
    INSERT INTO public.users (id,status) VALUES (buyer,'ACTIVE'),(maker,'ACTIVE');
    INSERT INTO public.user_profiles (user_id) VALUES (buyer),(maker);
    INSERT INTO public.user_identity_bindings (issuer,subject,user_id)
        VALUES ('http://127.0.0.1:8192/','cart-synthetic-buyer',buyer);
    INSERT INTO public.user_identity_current (user_id,issuer,subject)
        VALUES (buyer,'http://127.0.0.1:8192/','cart-synthetic-buyer');
    INSERT INTO public.workspaces (id,owner_type,owner_user_id) VALUES (workspace,'USER',maker);
    INSERT INTO public.workspace_memberships (workspace_id,user_id,role,status)
        VALUES (workspace,maker,'ADMIN','ACTIVE');
    INSERT INTO public.workspace_membership_scopes (workspace_id,user_id,scope)
        VALUES (workspace,maker,'LISTINGS'), (workspace,maker,'READY_MADE_PRODUCTS'), (workspace,maker,'PROJECTS');
    INSERT INTO account_cart.offer(id,workspace_id,label)
        VALUES ('kiln-cup',workspace,'Kiln Cup');
    INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch)
        VALUES ('kiln-cup','blue','kiln-blue','Blue glaze',2400,12,'#365899');
    INSERT INTO account_cart.template_revision(offer_id,revision,configuration)
        VALUES ('kiln-cup',1,'{"zones":[{"id":"face","operation":"print","tools":["image"]}]}'::jsonb);
    IF to_regclass('account_cart.design_resource_slot') IS NOT NULL THEN
        INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,label,layout_format,canonical_bytes)
            VALUES (buyer,design,1,'kiln-cup','blue',1,'Checker motif',1,
                convert_to('synthetic-layout-v1|face/print/image/center|slot:0','UTF8'));
    ELSE
        -- A populated legacy upgrade keeps its original immutable format; never promote it in place.
        INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,label,canonical_bytes)
            VALUES (buyer,design,1,'kiln-cup','blue',1,'Checker motif',
                convert_to('fixture-render-v1|kiln-cup@1|face/print/image/center|image:png:c7d7f035cb9bdc77a79d7a67f51916fe5c82e3f4222a8e3bc3ee96f4ad4b5f21','UTF8'));
    END IF;
    INSERT INTO account_cart.asset(owner_id,asset_id,kind,object_key,byte_count,sha256)
        VALUES (buyer,asset,'IMAGE','91940aca-2a1a-49ce-849f-639f792ee201',90,
                'c7d7f035cb9bdc77a79d7a67f51916fe5c82e3f4222a8e3bc3ee96f4ad4b5f21');
    INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (buyer,design,1,asset);
    IF to_regclass('account_cart.design_resource_slot') IS NOT NULL THEN
        INSERT INTO account_cart.design_resource_slot(owner_id,design_id,revision,slot,asset_id) VALUES (buyer,design,1,0,asset);
    END IF;
    INSERT INTO account_cart.cart(owner_id,version) VALUES (buyer,1);
    INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,design_id,design_revision,quantity)
        VALUES (buyer,'50000000-0000-4000-8000-000000000001','kiln-cup','blue',NULL,NULL,2),
               (buyer,'50000000-0000-4000-8000-000000000002','kiln-cup','blue',design,1,1);
    INSERT INTO account_cart_fixture.bootstrap(version) VALUES (1);
END $seed$;
COMMIT;
