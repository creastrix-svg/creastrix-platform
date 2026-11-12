-- Optional extension of the synthetic fixture, NOT a migration. Never reseed either private cart.
BEGIN;
DO $guard$
BEGIN
    IF current_database() <> 'creastrix_account_cart_local' OR current_user <> 'account_cart_local_app' THEN
        RAISE EXCEPTION 'Requires the dedicated synthetic account-cart database';
    END IF;
    IF NOT EXISTS (SELECT FROM account_cart_fixture.bootstrap WHERE version=1)
       OR NOT EXISTS (SELECT FROM account_cart.write_scope WHERE id='6651617f-080f-4a4b-bdef-2d77ba1c85cf') THEN
        RAISE EXCEPTION 'Install the ordinary synthetic buyer and lane first';
    END IF;
END $guard$;
CREATE TABLE IF NOT EXISTS account_cart_fixture.two_buyers (
    singleton boolean PRIMARY KEY CHECK (singleton), next_is_b boolean NOT NULL DEFAULT false
);
LOCK TABLE account_cart_fixture.two_buyers IN EXCLUSIVE MODE;
DO $seed$
DECLARE
    buyer_b uuid := '10000000-0000-4000-8000-000000000003';
BEGIN
    IF EXISTS (SELECT FROM account_cart_fixture.two_buyers) THEN RETURN; END IF;
    -- Fail on ANY existing identity or User collision; never attach another credential to a pre-existing User.
    INSERT INTO public.users(id,status) VALUES (buyer_b,'ACTIVE');
    INSERT INTO public.user_profiles(user_id) VALUES (buyer_b);
    INSERT INTO public.user_identity_bindings(issuer,subject,user_id)
        VALUES ('http://127.0.0.1:8192/','cart-synthetic-buyer-b',buyer_b);
    INSERT INTO public.user_identity_current(user_id,issuer,subject)
        VALUES (buyer_b,'http://127.0.0.1:8192/','cart-synthetic-buyer-b');
    INSERT INTO account_cart.cart(owner_id) VALUES (buyer_b);
    INSERT INTO account_cart_fixture.two_buyers(singleton) VALUES (true);
END $seed$;
-- A local operator arms ONE B authorization on the reserved lane. A is the default.
-- Lock even on the A path to serialize selection against another authorization or operator change.
CREATE OR REPLACE FUNCTION account_cart_fixture.consume_next_buyer_b() RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE selected_b boolean;
BEGIN
    SELECT next_is_b INTO STRICT selected_b FROM account_cart_fixture.two_buyers WHERE singleton FOR UPDATE;
    IF selected_b THEN UPDATE account_cart_fixture.two_buyers SET next_is_b=false WHERE singleton; END IF;
    RETURN selected_b;
END $$;
COMMIT;
