package com.creastrix.platform.accountcart;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Kind;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Stored;
import static com.creastrix.platform.accountcart.LocalCartCommands.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/** Only the synthetic lane constructs this store. All metadata transitions run under the caller's
 * scope + eligibility locks. No filesystem operation or decoder runs under those locks. */
final class LocalStudioDesignStore {
    private final JdbcTemplate db;
    private final PrivateCartAssetStore files;
    LocalStudioDesignStore(JdbcTemplate db,PrivateCartAssetStore files) { this.db=db; this.files=files; }
    record Slot(UUID asset,Stored ref) {}
    record Catalog(String offer,String variant) {}
    record Reservation(Catalog catalog,boolean published,List<Slot> slots) {}
    record Line(UUID design,String sku,long template,byte[] layout,List<Slot> slots) {}

    private Catalog catalog(StudioDesign content) {
        return db.query("""
            SELECT v.offer_id,v.id FROM account_cart.variant v JOIN account_cart.template_revision t
            ON t.offer_id=v.offer_id AND t.revision=? WHERE v.sku=? AND v.offer_id='loop' AND v.id=v.sku
            AND t.revision=2 AND t.configuration=?::jsonb
            AND (t.configuration->'variants') @> jsonb_build_array(v.id)
            """,(r,n)->new Catalog(r.getString(1),r.getString(2)),content.template,content.sku,StudioDesign.TEMPLATE_V2)
                .stream().findFirst().orElseThrow(InvalidBatch::new);
    }
    private List<Slot> slots(UUID owner,UUID id,boolean published) {
        String sql=published?"""
            SELECT s.slot,a.asset_id,a.object_key,a.kind,a.byte_count,a.sha256
              FROM account_cart.design_resource_slot s JOIN account_cart.asset a USING(owner_id,asset_id)
              WHERE s.owner_id=? AND s.design_id=? AND s.revision=1 AND a.completed ORDER BY s.slot LIMIT 9
            """:"""
            SELECT s.slot,s.asset_id,s.asset_id,a.kind,s.byte_count,s.sha256
              FROM account_cart.studio_stage_slot s JOIN account_cart.asset a USING(owner_id,asset_id)
              WHERE s.owner_id=? AND s.stage_id=? ORDER BY s.slot LIMIT 9
            """;
        return db.query(sql,(r,i)->{
            if(r.getInt(1)!=i || i>=8) throw new Unavailable();
            return new Slot(r.getObject(2,UUID.class),new Stored(r.getObject(3,UUID.class),
                    Kind.valueOf(r.getString(4)),Math.toIntExact(r.getLong(5)),r.getString(6)));
        },owner,id);
    }
    Reservation reserve(UUID owner,UUID id,StudioDesign content) {
        if(id==null) throw new InvalidBatch();
        var cat=catalog(content);
        var previous=db.query("""
            SELECT offer_id,variant_id,template_revision,canonical_bytes,TRUE FROM account_cart.design_revision
                WHERE owner_id=? AND design_id=? AND revision=1
            UNION ALL SELECT offer_id,variant_id,template_revision,layout,FALSE FROM account_cart.studio_stage
                WHERE owner_id=? AND stage_id=?
            """,(r,n)->{
                if(!cat.offer.equals(r.getString(1)) || !cat.variant.equals(r.getString(2)) ||
                        content.template!=r.getLong(3) || !Arrays.equals(content.layout,r.getBytes(4))) throw new Conflict();
                return r.getBoolean(5);
            },owner,id,owner,id);
        if(previous.size()>1) throw new Unavailable();
        if(previous.isEmpty()) {
            db.update("INSERT INTO account_cart.studio_stage VALUES (?,?,?,?,?,?)",owner,id,cat.offer,cat.variant,content.template,content.layout);
            int position=0;
            for(var resource:content.resources) {
                // Server-created key is private, not a derivation of a client ID or a content hash.
                UUID asset=UUID.randomUUID();
                db.update("INSERT INTO account_cart.asset(owner_id,asset_id,kind) VALUES(?,?,?)",owner,asset,resource.kind().name());
                db.update("INSERT INTO account_cart.studio_stage_slot VALUES (?,?,?,?,?,?)",owner,id,position++,asset,
                        resource.bytes().length,resource.digest());
            }
        }
        boolean published=!previous.isEmpty() && previous.getFirst();
        var slots=slots(owner,id,published);
        if(slots.size()!=content.resources.size()) throw new Conflict();
        for(int i=0;i<slots.size();i++) {
            var ref=slots.get(i).ref; var resource=content.resources.get(i);
            if(resource.kind()!=ref.kind() || resource.bytes().length!=ref.byteCount() || !resource.digest().equals(ref.sha256()))
                throw new Conflict();
        }
        return new Reservation(cat,published,slots);
    }
    void ensureBytes(Reservation stage,StudioDesign content) {
        try {
            for(int i=0;i<stage.slots.size();i++) {
                var ref=stage.slots.get(i).ref; var bytes=content.resources.get(i).bytes();
                if(!stage.published) {
                    try {
                        if(!files.writeNew(ref.objectKey(),ref.kind(),bytes).equals(ref)) throw new Unavailable();
                        continue;
                    } catch(FileAlreadyExistsException racedOrRetried) { /* Must compare the exact original. */ }
                }
                if(!Arrays.equals(files.read(ref),bytes)) throw new Conflict();
            }
        } catch(IOException unavailable) { throw new Unavailable(); }
    }
    void publish(UUID owner,UUID id,StudioDesign content,Reservation verified) {
        var current=reserve(owner,id,content);
        if(!current.catalog.equals(verified.catalog) || !current.slots.equals(verified.slots)) throw new Conflict();
        if(current.published) return;
        db.update("""
            INSERT INTO account_cart.design_revision(owner_id,design_id,revision,offer_id,variant_id,template_revision,
                label,canonical_bytes,layout_format) VALUES(?,?,1,?,?,?,'Studio design',?,1)
            """,owner,id,current.catalog.offer,current.catalog.variant,content.template,content.layout);
        int i=0;
        for(var slot:current.slots) {
            var ref=slot.ref;
            if(db.update("UPDATE account_cart.asset SET object_key=?,byte_count=?,sha256=? WHERE owner_id=? AND asset_id=? AND NOT completed",
                    ref.objectKey(),ref.byteCount(),ref.sha256(),owner,slot.asset)!=1) throw new Unavailable();
            db.update("INSERT INTO account_cart.design_asset(owner_id,design_id,revision,asset_id) VALUES (?,?,1,?)",owner,id,slot.asset);
            db.update("INSERT INTO account_cart.design_resource_slot VALUES (?,?,1,?,?)",owner,id,i++,slot.asset);
        }
        db.update("DELETE FROM account_cart.studio_stage WHERE owner_id=? AND stage_id=?",owner,id);
    }
    Line line(UUID owner,UUID line) {
        return db.query("""
            SELECT d.design_id,v.sku,d.template_revision,d.canonical_bytes FROM account_cart.cart_line l
            JOIN account_cart.variant v ON (v.offer_id,v.id)=(l.offer_id,l.variant_id)
            JOIN account_cart.design_revision d ON (d.owner_id,d.design_id,d.revision)=(l.owner_id,l.design_id,l.design_revision)
            JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
            WHERE l.owner_id=? AND l.id=? AND d.offer_id='loop' AND d.revision=1 AND d.layout_format=1
               AND t.revision=2 AND t.configuration=?::jsonb AND (t.configuration->'variants') @> jsonb_build_array(v.id)
               AND octet_length(d.canonical_bytes)<=65536
            """,(r,n)->new Line(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getBytes(4),List.of()),
                owner,line,StudioDesign.TEMPLATE_V2)
                .stream().findFirst().map(l->new Line(l.design,l.sku,l.template,l.layout,slots(owner,l.design,true))).orElse(null);
    }
    ObjectNode restore(Line line) {
        var data=new ArrayList<StudioDesign.Resource>(); int total=0;
        try {
            for(var slot:line.slots) {
                var ref=slot.ref;
                total=Math.addExact(total,4*((ref.byteCount()+2)/3)); if(total>StudioDesign.MAX_WIRE) throw new Unavailable();
                data.add(new StudioDesign.Resource(ref.kind(),files.read(ref),ref.sha256()));
            }
            return StudioDesign.restore(line.sku,line.template,line.layout,data);
        } catch(IOException unavailable) { throw new Unavailable(); }
    }
}
