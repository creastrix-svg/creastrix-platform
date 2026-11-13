-- Synthetic single-device lane and known-rollback fault control. Not a Flyway migration or HTTP endpoint.
BEGIN;
DO $guard$
BEGIN
    IF current_database()<>'creastrix_account_cart_local' OR current_user<>'account_cart_local_app'
       OR to_regclass('account_cart.lane_claim') IS NULL THEN
        RAISE EXCEPTION 'Requires the dedicated synthetic V22 account-cart database';
    END IF;
END $guard$;
INSERT INTO account_cart.write_scope(id,generation,owner_id)
    VALUES ('6651617f-080f-4a4b-bdef-2d77ba1c85cf',1,NULL) ON CONFLICT(id) DO NOTHING;
CREATE SCHEMA IF NOT EXISTS account_cart_fixture;
-- nextval survives a failed transaction, so exactly N real bind commits fail.
CREATE SEQUENCE IF NOT EXISTS account_cart_fixture.bind_attempt AS bigint START 1 NO CYCLE;
CREATE TABLE IF NOT EXISTS account_cart_fixture.bind_stop (id int PRIMARY KEY CHECK (id=1), stop_at bigint NOT NULL);
INSERT INTO account_cart_fixture.bind_stop VALUES (1,0) ON CONFLICT(id) DO NOTHING;
CREATE OR REPLACE FUNCTION account_cart_fixture.fail_next_bindings(number int) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF number IS NULL OR number NOT BETWEEN 0 AND 2 THEN RAISE EXCEPTION 'Expected 0, 1 or 2'; END IF;
    UPDATE account_cart_fixture.bind_stop SET stop_at=number+
        (SELECT CASE WHEN is_called THEN last_value ELSE last_value-1 END FROM account_cart_fixture.bind_attempt)
        WHERE id=1;
END $$;
CREATE OR REPLACE FUNCTION account_cart_fixture.bind_rollback() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF nextval('account_cart_fixture.bind_attempt') <= (SELECT stop_at FROM account_cart_fixture.bind_stop WHERE id=1) THEN
        RAISE EXCEPTION 'Synthetic deferred cart binding rollback' USING ERRCODE='PCA01',
            SCHEMA='account_cart_fixture', CONSTRAINT='local_cart_bind_rollback';
    END IF;
    RETURN NULL;
END $$;
DO $trigger$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_trigger WHERE tgrelid='account_cart.lane_claim'::regclass AND tgname='local_fixture_deferred_bind') THEN
        CREATE CONSTRAINT TRIGGER local_fixture_deferred_bind AFTER UPDATE ON account_cart.lane_claim
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.state='BOUND' AND OLD.state<>'BOUND')
            EXECUTE FUNCTION account_cart_fixture.bind_rollback();
    END IF;
END $trigger$;
COMMIT;
