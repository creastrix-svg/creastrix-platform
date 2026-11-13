package com.creastrix.platform.accountcart;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Buyer-owned, local-only library. Callers hold the session write scope and user
 * lock through metadata commit. No catalog I/O or file reads run inside that lock. */
public final class SavedDesignLibrary {
    public enum Action { SAVE, COPY, DELETE }
    public record Change(Action action, UUID id, long expectedVersion, UUID designId, UUID copyId) {}
    public record Result(UUID id,String version) {}
    record Decision(String state, Result result) {
        Result confirmed() {
            return switch(state){
                case "APPLIED" -> result;
                case "STALE" -> throw new LocalCartCommands.Conflict();
                case "REJECTED" -> throw new LocalCartCommands.InvalidBatch();
                default -> throw new LocalCartCommands.Unavailable();
            };
        }
    }
    public record Item(UUID id,String version,String sku,String offerId,String variantId,String offerLabel,String variantLabel,String caption) {
        @Override public String toString(){return "LibraryItem[redacted]";}
    }
    public record Opened(Item item,ObjectNode design) {}
    record Entry(Item item, UUID design) {}
    private record Snapshot(String offer,String variant,String caption) {}
    private final JdbcTemplate db;
    private static final int LIMIT=200;
    SavedDesignLibrary(JdbcTemplate db) { this.db=db; }

    private static final String PROJECTION="""
            SELECT e.id,e.version,v.sku,e.offer_id,e.variant_id,o.label AS offer_label,v.label AS variant_label,e.design_id,e.caption
              FROM account_cart.library_entry e JOIN account_cart.offer o ON o.id=e.offer_id
              JOIN account_cart.variant v ON (v.offer_id,v.id)=(e.offer_id,e.variant_id)
             WHERE e.owner_id=? AND e.design_id IS NOT NULL
            """;
    private static Entry entry(java.sql.ResultSet r,int number) throws java.sql.SQLException {
        return new Entry(new Item(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),
                r.getString(5),r.getString(6),r.getString(7),r.getString(9)),r.getObject(8,UUID.class));
    }
    List<Item> list(UUID owner) {
        return db.query(PROJECTION+" ORDER BY e.updated_at DESC,e.id LIMIT ?",SavedDesignLibrary::entry,owner,LIMIT)
                .stream().map(Entry::item).toList();
    }
    Entry current(UUID owner,UUID id) {
        return db.query(PROJECTION+" AND e.id=?",SavedDesignLibrary::entry,owner,id).stream().findFirst().orElse(null);
    }
    private Snapshot snapshot(UUID owner,UUID id) {
        return db.query("""
                SELECT d.offer_id,d.variant_id,d.canonical_bytes FROM account_cart.design_revision d JOIN account_cart.template_revision t
                  ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
                 WHERE d.owner_id=? AND d.design_id=? AND d.revision=1 AND d.layout_format=1 AND octet_length(d.canonical_bytes)<=65536
                   AND account_cart.studio_v2(t.configuration,t.revision,d.variant_id)
                """,(r,n)->new Snapshot(r.getString(1),r.getString(2),StudioDesign.caption(r.getBytes(3))),owner,id)
                .stream().findFirst().orElseThrow(LocalCartCommands.InvalidBatch::new);
    }
    private void insert(UUID owner,UUID id,UUID design,Snapshot target) {
        if(db.queryForObject("SELECT count(*) FROM account_cart.library_entry WHERE owner_id=? AND design_id IS NOT NULL",
                Long.class,owner)>=LIMIT)throw new LocalCartCommands.InvalidBatch();
        if(db.update("""
                INSERT INTO account_cart.library_entry(owner_id,id,version,offer_id,variant_id,design_id,design_revision,caption)
                  VALUES(?,?,1,?,?,?,1,?) ON CONFLICT DO NOTHING
                """,owner,id,target.offer,target.variant,design,target.caption)!=1)throw new LocalCartCommands.Conflict();
    }
    Decision change(UUID owner,UUID intent,Change c) {
        if(intent==null || c==null || c.action==null || c.id==null || c.expectedVersion<0
                || (c.action==Action.SAVE)!=(c.designId!=null) || (c.action==Action.COPY)!=(c.copyId!=null)
                || (c.action!=Action.SAVE && c.expectedVersion==0) || c.id.equals(c.copyId))
            throw new LocalCartCommands.InvalidBatch();
        var action=c.action.name().toLowerCase(java.util.Locale.ROOT);
        UUID resultId=c.action==Action.COPY?c.copyId:c.id;
        var previous=db.query("""
                SELECT action=? AND entry_id=? AND expected_version=? AND design_id IS NOT DISTINCT FROM ?::uuid
                    AND copy_id IS NOT DISTINCT FROM ?::uuid,result_version,state FROM account_cart.library_receipt
                    WHERE owner_id=? AND intent_id=?
                """,(r,n)->{
                    if(!r.getBoolean(1))throw new LocalCartCommands.Conflict();
                    return new Decision(r.getString(3),new Result(resultId,r.getString(2)));
                },action,c.id,c.expectedVersion,c.designId,c.copyId,owner,intent);
        if(!previous.isEmpty())return previous.getFirst();
        Long version=null;
        String state="APPLIED";
        // These two semantic exceptions can only precede the first successful
        // metadata mutation. Unexpected DB/commit errors must still roll back.
        try {version=applyNew(owner,c);}
        catch(LocalCartCommands.InvalidBatch refused){state="REJECTED";}
        catch(LocalCartCommands.Conflict stale){state="STALE";}
        db.update("""
                INSERT INTO account_cart.library_receipt(owner_id,intent_id,action,entry_id,expected_version,design_id,copy_id,result_version,state)
                    VALUES (?,?,?,?,?,?,?,?,?)
                """,owner,intent,action,c.id,c.expectedVersion,c.designId,c.copyId,version,state);
        return new Decision(state,new Result(resultId,version==null?null:version.toString()));
    }
    private long applyNew(UUID owner,Change c) {
        long version;
        if(c.expectedVersion==0) {
            insert(owner,c.id,c.designId,snapshot(owner,c.designId));version=1;
        }else{
            Entry existing=current(owner,c.id);
            if(existing==null || !existing.item.version.equals(Long.toString(c.expectedVersion)))
                throw new LocalCartCommands.Conflict();
            if(c.action==Action.COPY){
                insert(owner,c.copyId,existing.design,new Snapshot(existing.item.offerId,existing.item.variantId,existing.item.caption));
                version=1;
            }else{
                String caption=null;
                if(c.action==Action.SAVE){
                    var target=snapshot(owner,c.designId);
                    if(!Objects.equals(existing.item.offerId,target.offer) || !Objects.equals(existing.item.variantId,target.variant))
                        throw new LocalCartCommands.InvalidBatch();
                    caption=target.caption;
                }
                version=Math.incrementExact(c.expectedVersion);
                if(db.update("""
                        UPDATE account_cart.library_entry SET version=?,updated_at=clock_timestamp(),design_id=?,design_revision=?,caption=?
                         WHERE owner_id=? AND id=? AND version=? AND design_id IS NOT NULL
                        """,version,c.designId,c.designId==null?null:1L,caption,owner,c.id,c.expectedVersion)!=1)
                    throw new LocalCartCommands.Conflict();
            }
        }
        return version;
    }
}
