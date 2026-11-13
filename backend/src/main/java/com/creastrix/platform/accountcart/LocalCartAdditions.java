package com.creastrix.platform.accountcart;

import java.util.List;
import java.util.UUID;

import com.creastrix.platform.accountcart.GuestCartMergePlanner.ExistingLine;
import com.creastrix.platform.accountcart.GuestCartMergePlanner.LineKey;
import com.creastrix.platform.accountcart.GuestCartMergePlanner.Plain;
import com.creastrix.platform.accountcart.GuestCartMergePlanner.Units;
import com.creastrix.platform.accountcart.LocalCartCommands.Conflict;
import com.creastrix.platform.accountcart.LocalCartCommands.InvalidBatch;
import com.creastrix.platform.accountcart.LocalCartCommands.Addition;
import com.creastrix.platform.accountcart.LocalCartCommands.Unavailable;
import com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority;
import com.creastrix.platform.accountcart.LocalCartCommands.Receipt;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;
import org.springframework.jdbc.core.JdbcTemplate;

/** A direct click by a signed-in buyer. The caller holds the original write-scope
 * and eligible User locks throughout this transaction. A receipt, the cart version
 * and the line change commit together; the existing guest transfer protocol is separate. */
final class LocalCartAdditions {
    record Target(String offer, String variant, int stock, Long activeTemplate) {}
    private final JdbcTemplate db;
    private final LocalCartPreparation resolver;

    LocalCartAdditions(JdbcTemplate db,LocalCartPreparation resolver) { this.db = db; this.resolver=resolver; }

    static void validate(UUID intent,Addition request) {
        if (intent == null || request == null || request.sku() == null || request.sku().length() > 200
                || !request.sku().matches("[a-z0-9]+(-[a-z0-9]+)*")
                || (request.designId()==null)!=(request.designRevision()==null)
                || request.designRevision()!=null && request.designRevision()<=0
                || request.quantity() < 1 || request.quantity() > 1_000_000) throw new InvalidBatch();
    }
    Receipt previous(UUID owner,UUID intent,Addition request) {
        return db.query("""
                SELECT (owner_id=? AND sku=? AND quantity=? AND design_id IS NOT DISTINCT FROM ?::uuid
                       AND design_revision IS NOT DISTINCT FROM ?::bigint), state, applied_version, credited_line_id
                  FROM account_cart.direct_add_receipt WHERE intent_id=?
                """,(r,n)->{
                    if(!r.getBoolean(1)) throw new Conflict();
                    return new Receipt(r.getString(2),r.getObject(3,Long.class),r.getObject(4,UUID.class));
                },owner,request.sku(),request.quantity(),request.designId(),request.designRevision(),intent)
                .stream().findFirst().orElse(null);
    }

    /** Explicit old fixture only: the live local storefront supplies its own locked target. */
    Target fixtureTarget(String sku) {
        return db.query("""
                SELECT v.offer_id,v.id,v.stock,t.revision FROM account_cart.direct_add_variant d
                  JOIN account_cart.variant v USING (sku) JOIN account_cart.offer o ON o.id=v.offer_id
                  LEFT JOIN account_cart.template_revision t ON t.offer_id=v.offer_id
                    AND account_cart.studio_v2(t.configuration,t.revision,v.id)
                 WHERE d.sku=? AND o.listed FOR SHARE OF d,v,o
                """,(r,n)->new Target(r.getString(1),r.getString(2),r.getInt(3),r.getObject(4,Long.class)),sku)
                .stream().findFirst().orElse(null);
    }
    static Target liveTarget(SharedCatalogAuthority.Target t) {
        return new Target(t.offerId(),t.variantId(),t.stock(),t.templateRevision());
    }
    Receipt reject(UUID owner,UUID intent,Addition request) { return remember(owner,intent,request,null,null); }
    Receipt add(UUID owner, UUID intent, Addition request,Target variant) {
        if (variant==null || request.designId()!=null && (variant.activeTemplate()==null ||
                !Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS (SELECT FROM account_cart.design_revision d JOIN account_cart.template_revision t
                      ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                      WHERE d.owner_id=? AND d.design_id=? AND d.revision=? AND d.offer_id=? AND d.variant_id=?
                        AND d.layout_format=1 AND d.template_revision=? AND account_cart.studio_v2(t.configuration,t.revision,d.variant_id))
                    """,Boolean.class,owner,request.designId(),request.designRevision(),variant.offer(),variant.variant(),variant.activeTemplate()))))
            return reject(owner,intent,request);
        var versions = db.queryForList("SELECT version FROM account_cart.cart WHERE owner_id=? FOR UPDATE",Long.class,owner);
        // Every design consumes units of the same SKU. This is an add-time ceiling for
        // this cart only: adding a product never reserves or decrements merchant stock.
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT coalesce(sum(quantity),0) + ? <= ? FROM account_cart.cart_line
                 WHERE owner_id=? AND offer_id=? AND variant_id=? AND NOT removed
                """,Boolean.class,request.quantity(),variant.stock(),owner,variant.offer(),variant.variant())))
            return reject(owner,intent,request);

        LineKey key;
        try {
            key=request.designId()==null ? new LineKey(variant.offer(),variant.variant(),Plain.INSTANCE)
                    : resolver.key(owner,request.item());
        } catch(InvalidBatch unresolved) {
            // The owner, SKU and immutable revision already matched. Without a verified
            // canonical resource key the outcome is unknown, not a terminal rejection.
            throw new Unavailable();
        }
        var candidates=db.query("""
                SELECT l.id,l.created_at,l.offer_id,l.variant_id,l.quantity,l.design_id,l.design_revision,d.canonical_bytes,
                       d.template_revision,t.configuration->>'format'='loop-studio-v1' AS surface_layout,
                       account_cart.verified_design_resources(d.owner_id,d.design_id,d.revision) AS resource_classes
                  FROM account_cart.cart_line l LEFT JOIN account_cart.design_revision d
                    ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
                  LEFT JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                 WHERE l.owner_id=? AND l.offer_id=? AND l.variant_id=? AND NOT l.removed AND ((l.design_id IS NULL)=?)
                 ORDER BY l.id LIMIT ? FOR UPDATE OF l
                """,(r,n)->{
                    var match=PreparedCartImportStore.resolvedKey(r);
                    if(match==null) throw new Unavailable();
                    return new ExistingLine(r.getString("id"),r.getTimestamp("created_at").toInstant(),match,r.getLong("quantity"));
                },owner,variant.offer(),variant.variant(),request.designId()==null,PermanentCartReader.MAX_READ_LINES+1);
        if(candidates.size()>PermanentCartReader.MAX_READ_LINES) throw new Conflict();
        var plan=GuestCartMergePlanner.plan(candidates,List.of(new Units(key,request.quantity())));
        if (!CartLineCapacity.makeRoom(db,owner,plan)) throw new Conflict();
        // The original owner lock serializes first-cart creation. A rejected click
        // above must leave even a previously absent cart absent.
        if (versions.isEmpty()) db.update("INSERT INTO account_cart.cart(owner_id) VALUES (?)",owner);
        UUID line;
        if (!plan.insertions().isEmpty()) {
            line=UUID.randomUUID();
            db.update("""
                    INSERT INTO account_cart.cart_line(owner_id,id,offer_id,variant_id,quantity,design_id,design_revision)
                    VALUES (?,?,?,?,?,?,?)
                    """,owner,line,variant.offer(),variant.variant(),request.quantity(),request.designId(),request.designRevision());
        } else {
            var change=plan.replacements().getFirst();
            line=UUID.fromString(change.retainedId());
            for (String redundant: change.redundantIds())
                db.update("DELETE FROM account_cart.cart_line WHERE owner_id=? AND id=?",owner,UUID.fromString(redundant));
            db.update("UPDATE account_cart.cart_line SET quantity=? WHERE owner_id=? AND id=?",
                    change.quantity(),owner,UUID.fromString(change.retainedId()));
        }
        long after=Math.incrementExact(versions.isEmpty()?0L:versions.getFirst());
        db.update("UPDATE account_cart.cart SET version=? WHERE owner_id=?",after,owner);
        return remember(owner,intent,request,after,request.designId()==null?null:line);
    }

    private Receipt remember(UUID owner, UUID intent, Addition request, Long version,UUID line) {
        String state=version==null ? "REJECTED" : "APPLIED";
        // A key already used by a different owner is never adopted. A collision at
        // this boundary rolls back this entire transaction, including any new cart.
        if (db.update("""
                INSERT INTO account_cart.direct_add_receipt(intent_id,owner_id,sku,quantity,state,applied_version,design_id,design_revision,credited_line_id)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (intent_id) DO NOTHING
                """,intent,owner,request.sku(),request.quantity(),state,version,request.designId(),request.designRevision(),line)!=1) throw new Conflict();
        return new Receipt(state,version,line);
    }
}
