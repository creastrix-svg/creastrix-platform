package com.creastrix.platform.accountcart;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import com.creastrix.platform.accountcart.LocalCartCommands.*;
import com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority;
import org.springframework.jdbc.core.JdbcTemplate;

/** Caller holds the original write scope and User locks. Structural mutations of the
 * cart serialize on the cart version. One action either records its outcome together
 * with all changed rows or changes nothing; the receipt survives dismissal. */
final class LocalCartEdits {
    record Line(String sku,String offer,String variant,long quantity,boolean removed,UUID design,Long revision,Long template,long cartVersion) {}
    record Preflight(Receipt previous,String sku,List<UUID> resources) {
        Preflight(Receipt previous,String sku){this(previous,sku,List.of());}
    }
    private final JdbcTemplate db;
    LocalCartEdits(JdbcTemplate db){this.db=db;}
    static void validate(UUID intent,Edit e){
        if(intent==null || e==null || e.lineId()==null || e.action()==null || e.expectedVersion()<0
           || (e.action()==EditAction.QUANTITY)!=(e.quantity()!=null)
           || e.quantity()!=null && (e.quantity()<1 || e.quantity()>1_000_000)) throw new InvalidBatch();
    }
    Receipt previous(UUID owner,UUID intent,Edit e){
        return db.query("""
                SELECT owner_id=? AND line_id=? AND action=? AND expected_version=? AND quantity IS NOT DISTINCT FROM ?::integer,
                       state,applied_version FROM account_cart.line_edit_receipt WHERE intent_id=?
                """,(r,n)->{
                    if(!r.getBoolean(1))throw new Conflict();
                    return new Receipt(r.getString(2),r.getObject(3,Long.class));
                },owner,e.lineId(),operation(e),e.expectedVersion(),e.quantity(),intent).stream().findFirst().orElse(null);
    }
    Line current(UUID owner,UUID id){
        return db.query("""
                SELECT v.sku,l.offer_id,l.variant_id,l.quantity,l.removed,l.design_id,l.design_revision,d.template_revision,c.version
                  FROM account_cart.cart_line l JOIN account_cart.cart c USING(owner_id)
                  JOIN account_cart.variant v ON (v.offer_id,v.id)=(l.offer_id,l.variant_id)
                  LEFT JOIN account_cart.design_revision d ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
                 WHERE l.owner_id=? AND l.id=?
                """,(r,n)->new Line(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getBoolean(5),
                    r.getObject(6,UUID.class),r.getObject(7,Long.class),r.getObject(8,Long.class),r.getLong(9)),owner,id).stream().findFirst().orElse(null);
    }
    boolean stale(Line line,Edit e){
        return line==null || line.cartVersion()!=e.expectedVersion()
                || line.removed()!=(e.action()==EditAction.RESTORE || e.action()==EditAction.DISMISS);
    }
    Receipt staleReceipt(UUID owner,UUID intent,Edit e){return remember(owner,intent,e,"STALE",null);}
    Receipt apply(UUID owner,UUID intent,Edit e,SharedCatalogAuthority.Target target){
        var versions=db.queryForList("SELECT version FROM account_cart.cart WHERE owner_id=? FOR UPDATE",Long.class,owner);
        var line=current(owner,e.lineId());
        if(versions.isEmpty() || stale(line,e))return staleReceipt(owner,intent,e);
        if(e.action()==EditAction.QUANTITY && e.quantity()==line.quantity())
            return remember(owner,intent,e,"APPLIED",e.expectedVersion());
        boolean increasing=e.action()==EditAction.RESTORE || e.action()==EditAction.QUANTITY && e.quantity()>line.quantity();
        if(increasing) {
            long newCount=e.action()==EditAction.RESTORE ? line.quantity() : e.quantity();
            if(target==null || !line.sku().equals(target.sku()) || !line.offer().equals(target.offerId())
                || !line.variant().equals(target.variantId())
                || line.design()!=null && (target.templateRevision()==null || !Objects.equals(target.templateRevision(),line.template()))
                || !Boolean.TRUE.equals(db.queryForObject("""
                    SELECT coalesce(sum(quantity),0) + ? <= ? FROM account_cart.cart_line
                     WHERE owner_id=? AND offer_id=? AND variant_id=? AND id<>? AND NOT removed
                    """,Boolean.class,newCount,target.stock(),owner,line.offer(),line.variant(),e.lineId())))
                return remember(owner,intent,e,"REJECTED",null);
        }
        switch(e.action()){
            case QUANTITY -> db.update("UPDATE account_cart.cart_line SET quantity=? WHERE owner_id=? AND id=?",e.quantity(),owner,e.lineId());
            case REMOVE -> db.update("UPDATE account_cart.cart_line SET removed=true WHERE owner_id=? AND id=?",owner,e.lineId());
            case DISMISS -> db.update("DELETE FROM account_cart.cart_line WHERE owner_id=? AND id=?",owner,e.lineId());
            case RESTORE -> restore(owner,e.lineId(),line);
        }
        long after=Math.incrementExact(e.expectedVersion());
        db.update("UPDATE account_cart.cart SET version=? WHERE owner_id=?",after,owner);
        return remember(owner,intent,e,"APPLIED",after);
    }
    private record Candidate(UUID id, GuestCartMergePlanner.LineKey key,long quantity) {}
    /** A later add or guest transfer must not resurrect a saved-for-undo quantity.
     * Only the explicit restore reunites equivalent active rows with the original row
     * and design; different designs retain their identity and quantity. */
    private void restore(UUID owner,UUID original,Line line){
        var possible=db.query("""
                SELECT l.id,l.offer_id,l.variant_id,l.quantity,l.design_id,l.design_revision,d.canonical_bytes,d.template_revision,
                       t.configuration->>'format'='loop-studio-v1' AS surface_layout,
                       account_cart.verified_design_resources(l.owner_id,l.design_id,l.design_revision) AS resource_classes
                  FROM account_cart.cart_line l LEFT JOIN account_cart.design_revision d
                    ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
                  LEFT JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                 WHERE l.owner_id=? AND l.offer_id=? AND l.variant_id=? AND (l.id=? OR NOT l.removed) AND ((l.design_id IS NULL)=?)
                 ORDER BY l.id LIMIT 1001 FOR UPDATE OF l
                """,(r,n)->new Candidate(r.getObject("id",UUID.class),PreparedCartImportStore.resolvedKey(r),r.getLong("quantity")),
                owner,line.offer(),line.variant(),original,line.design()==null);
        if(possible.size()>1000 || possible.stream().anyMatch(row->row.key()==null))throw new Unavailable();
        var kept=possible.stream().filter(row->row.id().equals(original)).findFirst().orElseThrow(Unavailable::new);
        long total=kept.quantity();
        for(var row:possible){
            if(row.id().equals(original) || !kept.key().equals(row.key()))continue;
            total=Math.addExact(total,row.quantity());
            db.update("DELETE FROM account_cart.cart_line WHERE owner_id=? AND id=?",owner,row.id());
        }
        db.update("UPDATE account_cart.cart_line SET removed=false,quantity=? WHERE owner_id=? AND id=?",total,owner,original);
    }
    private static String operation(Edit e){return e.action().name().toLowerCase(Locale.ROOT);}
    private Receipt remember(UUID owner,UUID intent,Edit e,String state,Long version){
        int inserted=db.update("""
                INSERT INTO account_cart.line_edit_receipt(intent_id,owner_id,line_id,action,expected_version,quantity,state,applied_version)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (intent_id) DO NOTHING
                """,intent,owner,e.lineId(),operation(e),e.expectedVersion(),e.quantity(),state,version);
        if(inserted!=1)throw new Conflict();
        return new Receipt(state,version);
    }
}
