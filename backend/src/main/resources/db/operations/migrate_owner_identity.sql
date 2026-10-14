-- OFFLINE ONLY. Obtain explicit operation authorization and prove control of both exact identities.
-- Stop the single application first. See docs/authentication-provider-migration.md.
-- Supply psql variables from private operator input; never put identity values in source/evidence.
-- ON_ERROR_STOP prevents COMMIT after any failed guard. Unknown commit requires receipt reconciliation.
\set ON_ERROR_STOP on
BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
SET LOCAL transaction_timeout = '15s';
SELECT public.migrate_owner_identity(
    :'operation_id'::uuid, :'expected_user_id'::uuid, :'expected_generation'::bigint,
    :'old_issuer', :'old_subject', :'new_issuer', :'new_subject');
COMMIT;
