-- Only the internal registrar assigns class numbers, after comparing verified
-- private bytes. A class has no blob, path or independent retention pin.
CREATE SEQUENCE account_cart.verified_resource_number AS bigint NO CYCLE;
CREATE TABLE account_cart.verified_resource (
    owner_id uuid NOT NULL, asset_id uuid NOT NULL,
    comparison_version smallint NOT NULL CHECK (comparison_version=1),
    class_number bigint NOT NULL CHECK (class_number>0),
    completed boolean NOT NULL DEFAULT true CHECK (completed),
    PRIMARY KEY (owner_id,asset_id,comparison_version),
    FOREIGN KEY (owner_id,asset_id,completed)
        REFERENCES account_cart.asset(owner_id,asset_id,completed) ON DELETE CASCADE
);
CREATE INDEX asset_content_candidates ON account_cart.asset(owner_id,kind,byte_count,sha256) WHERE completed;
CREATE FUNCTION account_cart.keep_verified_resource() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' AND NOT EXISTS (SELECT FROM account_cart.asset
        WHERE (owner_id,asset_id)=(OLD.owner_id,OLD.asset_id)) THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'Resource equivalence follows the immutable object' USING ERRCODE='23514';
END $$;
CREATE TRIGGER preserve_verified_resource BEFORE DELETE OR UPDATE ON account_cart.verified_resource
    FOR EACH ROW EXECUTE FUNCTION account_cart.keep_verified_resource();
CREATE TRIGGER no_bulk_forget BEFORE TRUNCATE ON account_cart.verified_resource
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();

-- NULL is intentional: pre-existing private designs remain readable, but
-- arbitrary legacy payloads must not be silently promoted to layout-v1.
-- layout-v1 describes validated, handle-independent structure. A slot identifies
-- an occurrence in that structure (including repeated use of the same bytes).
ALTER TABLE account_cart.design_revision ADD COLUMN layout_format smallint CHECK(layout_format=1);
CREATE TABLE account_cart.design_resource_slot (
    owner_id uuid NOT NULL, design_id uuid NOT NULL, revision bigint NOT NULL,
    slot smallint NOT NULL CHECK(slot BETWEEN 0 AND 63), asset_id uuid NOT NULL,
    PRIMARY KEY(owner_id,design_id,revision,slot),
    FOREIGN KEY(owner_id,design_id,revision,asset_id)
        REFERENCES account_cart.design_asset ON DELETE CASCADE
);
CREATE TRIGGER preserve_resource_slots BEFORE INSERT OR DELETE ON account_cart.design_resource_slot
    FOR EACH ROW EXECUTE FUNCTION account_cart.preserve_version_resources();
CREATE TRIGGER immutable_resource_slots BEFORE UPDATE ON account_cart.design_resource_slot
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();
CREATE TRIGGER no_bulk_forget BEFORE TRUNCATE ON account_cart.design_resource_slot
    FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version();

-- No digest is a design key. Unknown version, a gap, missing slot or
-- unregistered resource cannot produce a key. Read all inputs in one snapshot.
CREATE FUNCTION account_cart.verified_design_resources(who uuid, design uuid, rev bigint)
RETURNS text[] LANGUAGE sql STABLE AS $$
    SELECT CASE WHEN d.layout_format=1
          AND NOT EXISTS (SELECT FROM account_cart.design_asset a
              WHERE (a.owner_id,a.design_id,a.revision)=(who,design,rev)
                AND NOT EXISTS (SELECT FROM account_cart.design_resource_slot s
                    WHERE (s.owner_id,s.design_id,s.revision,s.asset_id)=(who,design,rev,a.asset_id)))
          AND v.slots=v.verified AND v.slots=coalesce(v.last_slot+1,0)
          THEN coalesce(v.classes,ARRAY[]::text[]) END
      FROM account_cart.design_revision d
      CROSS JOIN LATERAL (
          SELECT count(*) slots,count(m.class_number) verified,max(s.slot) last_slot,
                 array_agg(a.kind||':'||m.comparison_version||':'||m.class_number ORDER BY s.slot) classes
            FROM account_cart.design_resource_slot s
            JOIN account_cart.asset a USING(owner_id,asset_id)
            LEFT JOIN account_cart.verified_resource m
              ON m.owner_id=a.owner_id AND m.asset_id=a.asset_id AND m.comparison_version=1
           WHERE (s.owner_id,s.design_id,s.revision)=(d.owner_id,d.design_id,d.revision)
      ) v WHERE (d.owner_id,d.design_id,d.revision)=(who,design,rev)
$$;
