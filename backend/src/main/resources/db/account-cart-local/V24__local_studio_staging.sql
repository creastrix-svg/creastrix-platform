-- A retry after interruption must find the original reserved private object, not upload a
-- second copy. The separate asset row owns the original upload clock. No source bytes here.
CREATE TABLE account_cart.studio_stage (
    owner_id uuid NOT NULL REFERENCES public.users,
    stage_id uuid NOT NULL,
    offer_id text NOT NULL, variant_id text NOT NULL, template_revision bigint NOT NULL,
    layout bytea NOT NULL CHECK (octet_length(layout) BETWEEN 1 AND 65536),
    PRIMARY KEY(owner_id,stage_id),
    FOREIGN KEY(offer_id,variant_id) REFERENCES account_cart.variant,
    FOREIGN KEY(offer_id,template_revision) REFERENCES account_cart.template_revision
);
CREATE TABLE account_cart.studio_stage_slot (
    owner_id uuid NOT NULL, stage_id uuid NOT NULL,
    slot smallint NOT NULL CHECK(slot BETWEEN 0 AND 7),
    asset_id uuid NOT NULL UNIQUE, byte_count int NOT NULL CHECK(byte_count BETWEEN 1 AND 10485760),
    sha256 text NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(owner_id,stage_id,slot),
    FOREIGN KEY(owner_id,stage_id) REFERENCES account_cart.studio_stage ON DELETE CASCADE,
    FOREIGN KEY(owner_id,asset_id) REFERENCES account_cart.asset
);
