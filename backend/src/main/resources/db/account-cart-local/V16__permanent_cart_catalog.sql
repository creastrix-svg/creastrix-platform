-- Explicit local commerce candidate, installed only alongside the real foundation migrations.
-- No demo users, offers, storage paths, web grants or retention jobs are installed by this migration.
CREATE SCHEMA account_cart;

CREATE TABLE account_cart.offer (
    id text PRIMARY KEY CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    workspace_id uuid NOT NULL REFERENCES public.workspaces,
    label text NOT NULL CHECK (length(trim(label)) BETWEEN 1 AND 200),
    currency text NOT NULL DEFAULT 'EUR' CHECK (currency = 'EUR'),
    listed boolean NOT NULL DEFAULT true
);
CREATE TABLE account_cart.variant (
    offer_id text NOT NULL REFERENCES account_cart.offer,
    id text NOT NULL CHECK (id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    sku text NOT NULL UNIQUE CHECK (sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND sku !~ '^design-[0-9]+$'),
    label text NOT NULL CHECK (length(trim(label)) BETWEEN 1 AND 200),
    cents int NOT NULL CHECK (cents BETWEEN 0 AND 1000000),
    stock int NOT NULL CHECK (stock BETWEEN 0 AND 1000000),
    swatch text NOT NULL CHECK (swatch ~ '^#[a-fA-F0-9]{6}$'),
    PRIMARY KEY (offer_id, id)
);
CREATE TABLE account_cart.template_revision (
    offer_id text NOT NULL REFERENCES account_cart.offer,
    revision bigint NOT NULL CHECK (revision > 0),
    configuration jsonb NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    PRIMARY KEY (offer_id, revision)
);

CREATE TABLE account_cart.asset (
    owner_id uuid NOT NULL REFERENCES public.users,
    asset_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('IMAGE','FONT')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- The store publishes complete immutable bytes before reporting its key.
    object_key uuid UNIQUE,
    byte_count bigint,
    sha256 text,
    completed boolean GENERATED ALWAYS AS (object_key IS NOT NULL) STORED,
    confirmed_count bigint NOT NULL DEFAULT 0 CHECK (confirmed_count >= 0),
    ever_confirmed boolean NOT NULL DEFAULT false,
    last_confirmed_removed_at timestamptz,
    PRIMARY KEY (owner_id, asset_id),
    UNIQUE (owner_id, asset_id, completed),
    CHECK ((object_key IS NULL AND byte_count IS NULL AND sha256 IS NULL)
           OR (object_key IS NOT NULL AND byte_count IS NOT NULL AND byte_count > 0
               AND sha256 IS NOT NULL AND sha256 ~ '^[0-9a-f]{64}$')),
    CHECK ((NOT ever_confirmed AND confirmed_count = 0 AND last_confirmed_removed_at IS NULL)
           OR (ever_confirmed AND object_key IS NOT NULL AND
               ((confirmed_count > 0 AND last_confirmed_removed_at IS NULL)
                OR (confirmed_count = 0 AND last_confirmed_removed_at IS NOT NULL))))
);
CREATE FUNCTION account_cart.keep_asset_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.owner_id, NEW.asset_id, NEW.started_at, NEW.kind) IS DISTINCT FROM
       (OLD.owner_id, OLD.asset_id, OLD.started_at, OLD.kind)
       OR (OLD.object_key IS NOT NULL AND
           (NEW.object_key, NEW.byte_count, NEW.sha256) IS DISTINCT FROM
           (OLD.object_key, OLD.byte_count, OLD.sha256)) THEN
        RAISE EXCEPTION 'Published resource identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER asset_identity BEFORE UPDATE ON account_cart.asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.keep_asset_identity();

CREATE TABLE account_cart.design_revision (
    owner_id uuid NOT NULL REFERENCES public.users,
    design_id uuid NOT NULL, revision bigint NOT NULL CHECK (revision > 0),
    offer_id text NOT NULL, variant_id text NOT NULL, template_revision bigint NOT NULL,
    label text NOT NULL CHECK (length(trim(label)) BETWEEN 1 AND 200),
    canonical_bytes bytea NOT NULL CHECK (octet_length(canonical_bytes) > 0),
    creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (owner_id, design_id, revision),
    UNIQUE (owner_id, design_id, revision, offer_id, variant_id),
    FOREIGN KEY (offer_id, variant_id) REFERENCES account_cart.variant,
    FOREIGN KEY (offer_id, template_revision) REFERENCES account_cart.template_revision
);
CREATE FUNCTION account_cart.immutable_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Version is immutable; create a new revision' USING ERRCODE='23514';
END $$;
CREATE TRIGGER template_immutable BEFORE UPDATE ON account_cart.template_revision
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER design_immutable BEFORE UPDATE ON account_cart.design_revision
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();

-- A confirmed resource edge belongs to one precise revision of the same user's design.
CREATE TABLE account_cart.design_asset (
    owner_id uuid NOT NULL, design_id uuid NOT NULL, revision bigint NOT NULL, asset_id uuid NOT NULL,
    completed boolean NOT NULL DEFAULT true CHECK (completed),
    PRIMARY KEY (owner_id, design_id, revision, asset_id),
    FOREIGN KEY (owner_id, design_id, revision) REFERENCES account_cart.design_revision ON DELETE CASCADE,
    FOREIGN KEY (owner_id, asset_id, completed) REFERENCES account_cart.asset(owner_id, asset_id, completed)
);
CREATE TRIGGER design_asset_immutable BEFORE UPDATE ON account_cart.design_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE FUNCTION account_cart.preserve_version_resources() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NOT EXISTS (SELECT FROM account_cart.design_revision d WHERE (d.owner_id,d.design_id,d.revision)
            =(NEW.owner_id,NEW.design_id,NEW.revision) AND d.creation_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION 'Attach resources when creating the immutable revision' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT FROM account_cart.design_revision d WHERE (d.owner_id,d.design_id,d.revision)
            =(OLD.owner_id,OLD.design_id,OLD.revision)) THEN
        RAISE EXCEPTION 'An existing design revision still owns this resource' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER preserve_version_resources BEFORE INSERT OR DELETE ON account_cart.design_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.preserve_version_resources();
CREATE FUNCTION account_cart.count_confirmed_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE account_cart.asset SET ever_confirmed=true, confirmed_count=confirmed_count+1,
            last_confirmed_removed_at=NULL WHERE owner_id=NEW.owner_id AND asset_id=NEW.asset_id;
    ELSE
        UPDATE account_cart.asset SET confirmed_count=confirmed_count-1,
            last_confirmed_removed_at=CASE WHEN confirmed_count=1 THEN clock_timestamp() END
            WHERE owner_id=OLD.owner_id AND asset_id=OLD.asset_id;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER count_design_asset AFTER INSERT OR DELETE ON account_cart.design_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.count_confirmed_reference();
-- Physical staging can finish first, but DB publication and its first live reference commit together.
CREATE FUNCTION account_cart.publication_requires_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.object_key IS NOT NULL AND (TG_OP='INSERT' OR OLD.object_key IS NULL)
        AND NOT EXISTS (SELECT FROM account_cart.design_asset d
            WHERE (d.owner_id,d.asset_id)=(NEW.owner_id,NEW.asset_id)) THEN
        RAISE EXCEPTION 'A published resource must have its first confirmed reference at commit'
            USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER asset_publication_reference AFTER INSERT OR UPDATE ON account_cart.asset
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_cart.publication_requires_reference();

CREATE TABLE account_cart.cart (
    owner_id uuid PRIMARY KEY REFERENCES public.users,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE account_cart.cart_line (
    owner_id uuid NOT NULL REFERENCES account_cart.cart, id uuid NOT NULL,
    offer_id text NOT NULL, variant_id text NOT NULL, design_id uuid, design_revision bigint,
    quantity bigint NOT NULL CHECK (quantity > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_id,id),
    CHECK ((design_id IS NULL) = (design_revision IS NULL)),
    FOREIGN KEY (offer_id,variant_id) REFERENCES account_cart.variant,
    FOREIGN KEY (owner_id,design_id,design_revision,offer_id,variant_id)
        REFERENCES account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id)
);

-- Only the session transition owner may provision, replace or revoke this row.
-- Nothing in this migration grants a writer or creates an active scope.
CREATE TABLE account_cart.write_scope (
    id uuid PRIMARY KEY,
    generation bigint NOT NULL CHECK (generation > 0),
    owner_id uuid REFERENCES public.users
);
CREATE FUNCTION account_cart.advance_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.id <> OLD.id OR NEW.generation <= OLD.generation THEN
        RAISE EXCEPTION 'Write scope generation cannot be reused' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER scope_generation BEFORE UPDATE OR DELETE ON account_cart.write_scope
    FOR EACH ROW EXECUTE FUNCTION account_cart.advance_scope();

CREATE TABLE account_cart.import_receipt (
    owner_id uuid NOT NULL REFERENCES public.users, batch_id uuid NOT NULL,
    request_bytes bytea, state text NOT NULL DEFAULT 'PENDING', applied_version bigint,
    creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (owner_id,batch_id),
    CHECK (request_bytes IS NULL OR octet_length(request_bytes)>0),
    CHECK ((state='PENDING' AND request_bytes IS NOT NULL AND applied_version IS NULL)
        OR (state='APPLIED' AND request_bytes IS NOT NULL AND applied_version IS NOT NULL AND applied_version>=0)
        OR (state='RETIRED' AND applied_version IS NULL))
);
CREATE FUNCTION account_cart.retain_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR OLD.state <> 'PENDING' OR NEW.state='PENDING'
       OR (NEW.owner_id,NEW.batch_id,NEW.request_bytes) IS DISTINCT FROM (OLD.owner_id,OLD.batch_id,OLD.request_bytes) THEN
        RAISE EXCEPTION 'An import identity or terminal outcome cannot be reused' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER receipt_transition BEFORE UPDATE OR DELETE ON account_cart.import_receipt
    FOR EACH ROW EXECUTE FUNCTION account_cart.retain_receipt();
CREATE TABLE account_cart.import_line (
    owner_id uuid NOT NULL, batch_id uuid NOT NULL, position int NOT NULL CHECK (position >= 0),
    offer_id text NOT NULL, variant_id text NOT NULL, design_id uuid, design_revision bigint,
    quantity bigint NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (owner_id,batch_id,position),
    CHECK ((design_id IS NULL)=(design_revision IS NULL)),
    FOREIGN KEY (owner_id,batch_id) REFERENCES account_cart.import_receipt,
    FOREIGN KEY (offer_id,variant_id) REFERENCES account_cart.variant,
    FOREIGN KEY (owner_id,design_id,design_revision,offer_id,variant_id)
        REFERENCES account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id)
);
-- Pin uploads even before they become a confirmed version's resources.
CREATE TABLE account_cart.pending_asset (
    owner_id uuid NOT NULL, batch_id uuid NOT NULL, asset_id uuid NOT NULL,
    PRIMARY KEY (owner_id,batch_id,asset_id),
    FOREIGN KEY (owner_id,batch_id) REFERENCES account_cart.import_receipt,
    FOREIGN KEY (owner_id,asset_id) REFERENCES account_cart.asset
);
CREATE FUNCTION account_cart.freeze_batch_content() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND EXISTS (SELECT FROM account_cart.import_receipt r
        WHERE (r.owner_id,r.batch_id)=(OLD.owner_id,OLD.batch_id) AND r.state<>'PENDING') THEN
        RETURN OLD; -- The terminal receipt, not the temporary pin, preserves replay evidence.
    END IF;
    IF TG_OP <> 'INSERT' OR NOT EXISTS (SELECT FROM account_cart.import_receipt r
        WHERE (r.owner_id,r.batch_id)=(NEW.owner_id,NEW.batch_id) AND r.state='PENDING'
        AND r.creation_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'Batch content is registered once' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER freeze_import_line BEFORE INSERT OR UPDATE OR DELETE ON account_cart.import_line
    FOR EACH ROW EXECUTE FUNCTION account_cart.freeze_batch_content();
CREATE TRIGGER freeze_pending_pin BEFORE INSERT OR UPDATE OR DELETE ON account_cart.pending_asset
    FOR EACH ROW EXECUTE FUNCTION account_cart.freeze_batch_content();
CREATE VIEW account_cart.asset_retention_facts AS
    SELECT a.*, (SELECT count(*) FROM account_cart.pending_asset p JOIN account_cart.import_receipt r
           USING(owner_id,batch_id) WHERE (p.owner_id,p.asset_id)=(a.owner_id,a.asset_id)
           AND r.state='PENDING') AS pending_count FROM account_cart.asset a;
