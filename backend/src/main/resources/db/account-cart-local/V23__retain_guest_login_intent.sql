-- A detached guest contribution can enter only one synthetic login. Keep the
-- opaque nonce even if a login is interrupted; no account or cart contents
-- are stored here. This marker grants no read, import or recovery rights.
CREATE TABLE account_cart.guest_login_intent (intent_id uuid PRIMARY KEY);
CREATE TRIGGER guest_login_intent_retained BEFORE UPDATE OR DELETE ON account_cart.guest_login_intent
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER guest_login_intent_no_truncate BEFORE TRUNCATE ON account_cart.guest_login_intent
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();
