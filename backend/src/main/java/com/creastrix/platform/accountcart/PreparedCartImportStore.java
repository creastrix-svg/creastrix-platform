package com.creastrix.platform.accountcart;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.creastrix.platform.accountcart.GuestCartMergePlanner.*;

/**
 * Local persistence primitive for already validated, frozen batches. Not a Spring bean or public
 * service. Only a reviewed server authority may supply an immutable admission; there is no default
 * scope bootstrap. A real preparation must have validated canonical bytes, exact asset identities
 * and referenced owner versions, not just browser IDs, labels or content hashes.
 */
final class PreparedCartImportStore {
    // Both generations belong to the original server admission. Never refresh
    // either from the database while trying to apply or acknowledge a batch.
    record Admission(UUID scope, long generation, UUID owner, long eligibilityGeneration) {}
    record Acknowledgement(long version) {}
    private record Receipt(String state, Long version) {}
    private record OfferVariant(String offer, String variant) {}
    private record Source(OfferVariant product, LineKey key, long quantity, UUID design, Long revision) {}
    private record Current(UUID id, Instant created, Source source) {}

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    PreparedCartImportStore(DataSource source) {
        db = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        // Own the commit/ack boundary; never depend on an uncommitted ambient preparation.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(12);
    }

    Acknowledgement apply(Admission admission, UUID batchId) {
        return apply(admission,batchId, ignored -> {});
    }

    Acknowledgement apply(Admission admission, UUID batchId, java.util.function.Consumer<JdbcTemplate> exactClaimGate) {
        return Objects.requireNonNull(transaction.execute(status -> {
            db.execute("SET LOCAL lock_timeout='3s'");
            db.execute("SET LOCAL statement_timeout='8s'");
            db.execute("SET LOCAL transaction_timeout='12s'");
            UUID owner = admission.owner();
            // Scope transitions and account suspension must serialize against these same rows.
            if (db.queryForList("""
                    SELECT 1 FROM account_cart.write_scope WHERE id=? AND generation=? AND owner_id=?
                    FOR NO KEY UPDATE
                    """, Integer.class, admission.scope(), admission.generation(), owner).isEmpty()
                    || db.queryForList("""
                            SELECT 1 FROM public.users WHERE id=? AND status='ACTIVE'
                                AND account_eligibility_generation=? FOR NO KEY UPDATE
                            """, Integer.class, owner, admission.eligibilityGeneration()).isEmpty())
                throw new IllegalStateException("CART_ADMISSION_REFUSED");

            exactClaimGate.accept(db);
            var receipts = db.query("""
                SELECT state,applied_version FROM account_cart.import_receipt
                WHERE owner_id=? AND batch_id=? FOR UPDATE
                """, (r,n) -> new Receipt(r.getString(1),r.getObject(2,Long.class)),owner,batchId);
            if (receipts.isEmpty()) throw new IllegalStateException("CART_IMPORT_NOT_PENDING");
            var receipt = receipts.getFirst();
            if (receipt.state().equals("APPLIED")) return new Acknowledgement(receipt.version());
            if (!receipt.state().equals("PENDING")) throw new IllegalStateException("CART_IMPORT_NOT_PENDING");

            var incoming = db.query("""
                SELECT l.offer_id,l.variant_id,l.quantity,l.design_id,l.design_revision,d.canonical_bytes,d.template_revision,
                  t.configuration->>'format'='loop-studio-v1' AS surface_layout,
                  account_cart.verified_design_resources(l.owner_id,l.design_id,l.design_revision) resource_classes
                FROM account_cart.import_line l LEFT JOIN account_cart.design_revision d
                  ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
                LEFT JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                WHERE l.owner_id=? AND l.batch_id=? ORDER BY l.position
                """, (r,n) -> source(r), owner,batchId);
            if (db.queryForObject("""
                    SELECT EXISTS(SELECT FROM account_cart.pending_asset p WHERE p.owner_id=? AND p.batch_id=?
                       AND NOT EXISTS(SELECT FROM account_cart.import_line i JOIN account_cart.design_asset a
                       ON (a.owner_id,a.design_id,a.revision)=(i.owner_id,i.design_id,i.design_revision)
                       WHERE i.owner_id=p.owner_id AND i.batch_id=p.batch_id AND a.asset_id=p.asset_id))
                    """,Boolean.class,owner,batchId)) throw new IllegalStateException("CART_UNRESOLVED_ASSET");
            if (!incoming.isEmpty())
                db.update("INSERT INTO account_cart.cart (owner_id) VALUES (?) ON CONFLICT (owner_id) DO NOTHING",owner);
            var versions = db.queryForList("SELECT version FROM account_cart.cart WHERE owner_id=? FOR UPDATE", Long.class, owner);
            long before = versions.isEmpty() ? 0L : versions.getFirst();
            var current = db.query("""
                SELECT l.id,l.created_at,l.offer_id,l.variant_id,l.quantity,l.design_id,l.design_revision,d.canonical_bytes,d.template_revision,
                  t.configuration->>'format'='loop-studio-v1' AS surface_layout,
                  account_cart.verified_design_resources(l.owner_id,l.design_id,l.design_revision) resource_classes
                FROM account_cart.cart_line l LEFT JOIN account_cart.design_revision d
                  ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
                LEFT JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                WHERE l.owner_id=? AND NOT l.removed ORDER BY l.id FOR UPDATE OF l
                """, (r,n) -> new Current(r.getObject("id",UUID.class),r.getTimestamp("created_at").toInstant(),
                    source(r)), owner);
            var decorated = new java.util.HashSet<OfferVariant>();
            for (Source line : incoming) {
                if (line.key()==null) throw new IllegalStateException("CART_UNRESOLVED_DESIGN");
                if (line.design()!=null) decorated.add(line.product());
            }
            // A legacy or unregistered design might match a new personalised line. Refuse
            // that case instead of guessing, but do not block a plain or unrelated addition.
            if (current.stream().map(Current::source).anyMatch(l -> l.key()==null && decorated.contains(l.product())))
                throw new IllegalStateException("CART_UNRESOLVED_DESIGN");
            var plan = plan(current.stream().filter(l -> l.source().key()!=null).map(l -> new ExistingLine(l.id().toString(),l.created(),
                    l.source().key(),l.source().quantity())).toList(),
                    incoming.stream().map(l -> new Units(l.key(),l.quantity())).toList());
            // Check the resulting cart rather than the batch length or an intermediate
            // count: coalescing duplicate existing rows can make room for a genuine new
            // line. All cart edits must hold the same owner/cart lock through commit.
            if (!CartLineCapacity.makeRoom(db,owner,plan)) throw new IllegalStateException("CART_LINE_LIMIT");
            var representatives = new HashMap<LineKey,Source>();
            incoming.forEach(l -> representatives.putIfAbsent(l.key(),l));
            for (var line : plan.insertions()) {
                var s = representatives.get(line.key());
                db.update("""
                    INSERT INTO account_cart.cart_line (owner_id,id,offer_id,variant_id,design_id,design_revision,quantity)
                    VALUES (?,?,?,?,?,?,?)
                    """,owner,UUID.randomUUID(),s.key().offerId(),s.key().variantId(),s.design(),s.revision(),line.quantity());
            }
            for (var change : plan.replacements()) {
                for (String id : change.redundantIds()) {
                    db.update("DELETE FROM account_cart.cart_line WHERE owner_id=? AND id=?",owner,UUID.fromString(id));
                }
                db.update("UPDATE account_cart.cart_line SET quantity=? WHERE owner_id=? AND id=?",
                        change.quantity(),owner,UUID.fromString(change.retainedId()));
            }
            long after = incoming.isEmpty() ? before : Math.addExact(before,1);
            if (after!=before) db.update("UPDATE account_cart.cart SET version=? WHERE owner_id=?",after,owner);
            db.update("UPDATE account_cart.import_receipt SET state='APPLIED',applied_version=? WHERE owner_id=? AND batch_id=?",
                    after,owner,batchId);
            // Cart lines reference complete immutable revisions. The terminal receipt keeps exact
            // batch identity, so temporary pins need not retain obsolete versions forever.
            db.update("DELETE FROM account_cart.import_line WHERE owner_id=? AND batch_id=?",owner,batchId);
            db.update("DELETE FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",owner,batchId);
            return new Acknowledgement(after);
        }));
    }

    static LineKey resolvedKey(java.sql.ResultSet row) throws java.sql.SQLException {
        return source(row).key();
    }

    private static Source source(java.sql.ResultSet row) throws java.sql.SQLException {
        UUID design = row.getObject("design_id",UUID.class);
        var product = new OfferVariant(row.getString("offer_id"),row.getString("variant_id"));
        Design key = Plain.INSTANCE;
        if (design != null) {
            var classes = row.getArray("resource_classes");
            if (classes == null) key = null;
            else {
                // No local IDs, filenames or client hashes. A slot's class number is meaningful
                // only within this locked owner's namespace and its kind/comparison version.
                var layout = row.getBytes("canonical_bytes");
                long template = row.getLong("template_revision");
                if(row.getBoolean("surface_layout")) {
                    layout=StudioDesign.comparisonLayout(layout);
                    if(layout==null) return new Source(product,null,row.getLong("quantity"),design,row.getObject("design_revision",Long.class));
                }
                String[] slots = (String[]) classes.getArray();
                int capacity = Math.addExact(20,layout.length);
                for (String slot : slots) capacity = Math.addExact(capacity,4+slot.length()); // SQL emits ASCII tokens.
                var bytes = ByteBuffer.allocate(capacity).putInt(1).putLong(template)
                        .putInt(layout.length).put(layout).putInt(slots.length);
                for (String slot : slots) bytes.putInt(slot.length()).put(slot.getBytes(StandardCharsets.US_ASCII));
                key = new CanonicalDesign(bytes.array());
            }
        }
        return new Source(product,key==null ? null : new LineKey(product.offer(),product.variant(),key),
                row.getLong("quantity"),design,row.getObject("design_revision",Long.class));
    }
}
