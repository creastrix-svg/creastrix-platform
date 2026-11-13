-- Successful outcomes from V30 remain valid. A refused request also owns its
-- intent: retrying it after a library version/limit changes cannot create a
-- new decision the buyer did not make.
ALTER TABLE account_cart.library_receipt
    ADD COLUMN state text NOT NULL DEFAULT 'APPLIED' CHECK(state IN ('APPLIED','STALE','REJECTED')),
    ALTER COLUMN result_version DROP NOT NULL,
    ADD CHECK ((state='APPLIED') = (result_version IS NOT NULL));
