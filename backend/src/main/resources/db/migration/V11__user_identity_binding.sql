-- Technical persistence for the approved bounded authentication slice.
-- Identity proof and admission are application responsibilities, not claims of raw-SQL authorization.
CREATE TABLE user_identity_bindings (
    issuer TEXT COLLATE "C" NOT NULL,
    subject TEXT COLLATE "C" NOT NULL,
    user_id UUID NOT NULL,
    CONSTRAINT user_identity_bindings_pk PRIMARY KEY (issuer, subject) NOT DEFERRABLE,
    CONSTRAINT user_identity_bindings_user_unique UNIQUE (user_id) NOT DEFERRABLE,
    CONSTRAINT user_identity_bindings_user_fk FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT user_identity_bindings_nonblank CHECK (btrim(issuer) <> '' AND btrim(subject) <> '')
);

-- No linking, rebind, deletion, expiration or automatic legacy-User backfill workflow exists.
CREATE FUNCTION user_identity_bindings_preserve() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Identity bindings are immutable and retained'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER user_identity_bindings_preserve
    BEFORE UPDATE OR DELETE ON user_identity_bindings
    FOR EACH ROW EXECUTE FUNCTION user_identity_bindings_preserve();

CREATE TRIGGER user_identity_bindings_preserve_on_truncate
    BEFORE TRUNCATE ON user_identity_bindings
    FOR EACH STATEMENT EXECUTE FUNCTION user_identity_bindings_preserve();
