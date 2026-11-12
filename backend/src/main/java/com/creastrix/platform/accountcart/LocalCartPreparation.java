package com.creastrix.platform.accountcart;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;

/** Called only inside a transaction holding the lane and eligibility locks for this internal User. */
final class LocalCartPreparation {
    private static final int MAX_LINES=1000, MAX_BATCH_BYTES=4*1024*1024;
    private static final Pattern SKU=Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private final JdbcTemplate db;
    LocalCartPreparation(JdbcTemplate db) { this.db=db; }

    private record Existing(byte[] identity, LocalCartCommands.Receipt receipt) {}
    private record Row(GuestCartMergePlanner.LineKey key,UUID design,Long revision,long quantity) {}
    private Existing existing(UUID owner,UUID batch) {
        if (batch==null) throw new LocalCartCommands.InvalidBatch();
        return db.query("SELECT request_bytes,state,applied_version FROM account_cart.import_receipt WHERE owner_id=? AND batch_id=?",
                (r,n)->new Existing(r.getBytes(1),new LocalCartCommands.Receipt(r.getString(2),r.getObject(3,Long.class))),owner,batch)
                .stream().findFirst().orElse(null);
    }
    LocalCartCommands.Receipt status(UUID owner,UUID batch) {
        var old=existing(owner,batch);
        return old==null ? new LocalCartCommands.Receipt("UNKNOWN",null) : old.receipt();
    }
    LocalCartCommands.Receipt retire(UUID owner,UUID batch) {
        var old=existing(owner,batch);
        if (old==null) {
            db.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,state) VALUES (?,?,'RETIRED')",owner,batch);
        } else if (old.receipt().state().equals("PENDING")) {
            db.update("UPDATE account_cart.import_receipt SET state='RETIRED' WHERE owner_id=? AND batch_id=?",owner,batch);
            db.update("DELETE FROM account_cart.import_line WHERE owner_id=? AND batch_id=?",owner,batch);
            db.update("DELETE FROM account_cart.pending_asset WHERE owner_id=? AND batch_id=?",owner,batch);
        } else return old.receipt();
        return new LocalCartCommands.Receipt("RETIRED",null);
    }
    LocalCartCommands.Receipt prepare(UUID owner,UUID batch,List<LocalCartCommands.Item> items) {
        if (batch==null || items==null || items.size()>MAX_LINES) throw new LocalCartCommands.InvalidBatch();
        var old=existing(owner,batch);
        if (old!=null && old.identity()==null) throw new LocalCartCommands.Conflict(); // retired without preparation
        var rows=new TreeMap<byte[],Row>(Arrays::compareUnsigned);
        int encodedSize=8;
        for (var item : items) {
            var part=resolve(owner,item);
            byte[] key=keyBytes(part.key());
            var previous=rows.get(key);
            if (previous==null) {
                encodedSize=Math.addExact(encodedSize,12+key.length);
                if (encodedSize>MAX_BATCH_BYTES) throw new LocalCartCommands.InvalidBatch();
                rows.put(key,part);
            } else {
                long total;
                try { total=Math.addExact(previous.quantity(),part.quantity()); }
                catch (ArithmeticException invalid) { throw new LocalCartCommands.InvalidBatch(); }
                rows.put(key,new Row(previous.key(),previous.design(),previous.revision(),total));
            }
        }
        final byte[] identity;
        try {
            var buffer=new ByteArrayOutputStream(encodedSize);
            var out=new DataOutputStream(buffer);
            out.writeInt(1); out.writeInt(rows.size());
            for (var entry : rows.entrySet()) { field(out,entry.getKey()); out.writeLong(entry.getValue().quantity()); }
            identity=buffer.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        if (old!=null) {
            if (!Arrays.equals(old.identity(),identity)) throw new LocalCartCommands.Conflict();
            return old.receipt();
        }
        db.update("INSERT INTO account_cart.import_receipt(owner_id,batch_id,request_bytes) VALUES (?,?,?)",owner,batch,identity);
        int position=0;
        for (var row : rows.values()) {
            db.update("""
                INSERT INTO account_cart.import_line(owner_id,batch_id,position,offer_id,variant_id,design_id,design_revision,quantity)
                VALUES (?,?,?,?,?,?,?,?)
                """,owner,batch,position++,row.key().offerId(),row.key().variantId(),row.design(),row.revision(),row.quantity());
            if (row.design()!=null) db.update("""
                INSERT INTO account_cart.pending_asset(owner_id,batch_id,asset_id)
                SELECT owner_id,?,asset_id FROM account_cart.design_asset WHERE owner_id=? AND design_id=? AND revision=?
                ON CONFLICT DO NOTHING
                """,batch,owner,row.design(),row.revision());
        }
        return new LocalCartCommands.Receipt("PENDING",null);
    }

    private Row resolve(UUID owner,LocalCartCommands.Item item) {
        if (item==null || item.sku()==null || item.sku().length()>200 || !SKU.matcher(item.sku()).matches()
                || item.quantity()<=0 || (item.designId()==null)!=(item.designRevision()==null)
                || item.designRevision()!=null && item.designRevision()<=0) throw new LocalCartCommands.InvalidBatch();
        // The query resolves current stable catalog identity even for retired listings. It never
        // credits a client price, arbitrary design label, browser handle or foreign owner's file.
        var result=db.query("""
            SELECT v.offer_id,v.id AS variant_id,?::bigint AS quantity,d.design_id,d.revision AS design_revision,
                   d.canonical_bytes,d.template_revision,
                   account_cart.verified_design_resources(d.owner_id,d.design_id,d.revision) AS resource_classes
            FROM account_cart.variant v
            LEFT JOIN account_cart.design_revision d ON d.owner_id=? AND d.design_id=? AND d.revision=?
                 AND (d.offer_id,d.variant_id)=(v.offer_id,v.id) AND octet_length(d.canonical_bytes)<=262144
            WHERE v.sku=?
            """,(r,n)->{
                UUID design=r.getObject("design_id",UUID.class);
                if (item.designId()!=null && design==null) throw new LocalCartCommands.InvalidBatch();
                var key=PreparedCartImportStore.resolvedKey(r);
                if (key==null) throw new LocalCartCommands.InvalidBatch();
                return new Row(key,design,r.getObject("design_revision",Long.class),item.quantity());
            },item.quantity(),owner,item.designId(),item.designRevision(),item.sku());
        return result.stream().findFirst().orElseThrow(LocalCartCommands.InvalidBatch::new);
    }
    private static byte[] keyBytes(GuestCartMergePlanner.LineKey key) {
        try {
            var buffer=new ByteArrayOutputStream(); var out=new DataOutputStream(buffer);
            field(out,key.offerId().getBytes(StandardCharsets.UTF_8));
            field(out,key.variantId().getBytes(StandardCharsets.UTF_8));
            if (key.design() instanceof GuestCartMergePlanner.CanonicalDesign design) { out.writeByte(1); field(out,design.bytes()); }
            else out.writeByte(0);
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static void field(DataOutputStream out,byte[] value) throws IOException {
        out.writeInt(value.length); out.write(value);
    }
}
