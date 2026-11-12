-- Catalog retirement is a visibility decision, not permission to recycle a stable
-- identity or to move an offer out of the Workspace whose membership governs it.
CREATE INDEX cart_line_owner_read_order ON account_cart.cart_line(owner_id,created_at,id);

CREATE FUNCTION account_cart.keep_offer_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (NEW.id,NEW.workspace_id) IS DISTINCT FROM (OLD.id,OLD.workspace_id) THEN
        RAISE EXCEPTION 'Catalog offer identity and Workspace are retained' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER offer_identity BEFORE UPDATE OR DELETE ON account_cart.offer
    FOR EACH ROW EXECUTE FUNCTION account_cart.keep_offer_owner();
CREATE FUNCTION account_cart.keep_variant_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR (NEW.offer_id,NEW.id,NEW.sku) IS DISTINCT FROM (OLD.offer_id,OLD.id,OLD.sku) THEN
        RAISE EXCEPTION 'Catalog variant identity and SKU are retained' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER variant_identity BEFORE UPDATE OR DELETE ON account_cart.variant
    FOR EACH ROW EXECUTE FUNCTION account_cart.keep_variant_identity();
CREATE TRIGGER template_retained BEFORE DELETE ON account_cart.template_revision
    FOR EACH ROW EXECUTE FUNCTION account_cart.immutable_version();

-- TRUNCATE skips row triggers, including retained receipt and reference count
-- checks. No lifecycle or erase operation in this local candidate uses it.
DO $guards$
DECLARE target text;
BEGIN
  FOREACH target IN ARRAY ARRAY['offer','variant','template_revision','asset','design_revision',
      'design_asset','cart','cart_line','write_scope','import_receipt','import_line','pending_asset']
  LOOP
    EXECUTE format('CREATE TRIGGER no_bulk_forget BEFORE TRUNCATE ON account_cart.%I
        FOR EACH STATEMENT EXECUTE FUNCTION account_cart.immutable_version()',target);
  END LOOP;
END $guards$;
