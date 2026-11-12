-- Opt-in only. No migration pre-provisions a live write scope or chooses a User.
CREATE TABLE account_cart.owner_namespace (
    owner_id uuid PRIMARY KEY REFERENCES public.users,
    namespace uuid NOT NULL UNIQUE,
    UNIQUE(owner_id,namespace)
);
CREATE TRIGGER namespace_retained BEFORE UPDATE OR DELETE ON account_cart.owner_namespace
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER namespace_no_truncate BEFORE TRUNCATE ON account_cart.owner_namespace
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();

-- Current claim only; the never-reused monotone scope generation fences previous claims.
-- A session proof is a SHA-256 of the server-supplied, rotated session ID, not a cookie authority.
CREATE TABLE account_cart.lane_claim (
    scope_id uuid PRIMARY KEY REFERENCES account_cart.write_scope,
    ticket uuid NOT NULL UNIQUE,
    target_generation bigint NOT NULL CHECK (target_generation>1),
    owner_id uuid NOT NULL, namespace uuid NOT NULL,
    eligibility_generation bigint NOT NULL CHECK (eligibility_generation>0),
    session_proof bytea NOT NULL CHECK (octet_length(session_proof)=32),
    state text NOT NULL CHECK (state IN ('PENDING','RETRYABLE','BOUND')),
    FOREIGN KEY(owner_id,namespace) REFERENCES account_cart.owner_namespace(owner_id,namespace)
);
