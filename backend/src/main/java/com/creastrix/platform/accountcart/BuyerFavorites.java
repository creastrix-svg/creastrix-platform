package com.creastrix.platform.accountcart;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private variant bookmarks. All calls run within the already-admitted buyer
 * transaction; the write-scope lock serializes membership, version and receipt. */
public final class BuyerFavorites {
    public static final int LIMIT = 1000;
    private static final Pattern SKU = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    public enum Operation { SET, UNSET, IMPORT }
    public record Snapshot(UUID ownerId, String version, List<String> skus) {}
    record Decision(Snapshot snapshot) {
        Snapshot confirmed() {
            if (snapshot == null) throw new LocalCartCommands.InvalidBatch();
            return snapshot;
        }
    }
    public record Change(Operation operation, List<String> skus) {
        public Change {
            if (operation == null || skus == null || skus.size() > LIMIT
                    || (operation != Operation.IMPORT && skus.size() != 1))
                throw new LocalCartCommands.InvalidBatch();
            var sorted = new TreeSet<String>();
            for (String sku : skus) {
                if (sku == null || sku.length() > 200 || !SKU.matcher(sku).matches() || !sorted.add(sku))
                    throw new LocalCartCommands.InvalidBatch();
            }
            skus = List.copyOf(sorted);
        }
        byte[] fingerprint() {
            // Newlines are outside SKU grammar. The operation is compared independently.
            try { return MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", skus).getBytes(StandardCharsets.US_ASCII)); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
    }

    private final JdbcTemplate db;
    BuyerFavorites(JdbcTemplate db) { this.db = db; }
    Snapshot snapshot(UUID owner) {
        long version = db.queryForObject("""
                SELECT COALESCE((SELECT version FROM account_cart.favorite_shelf WHERE owner_id=?),0)
                """, Long.class, owner);
        List<String> skus = db.queryForList("""
                SELECT sku FROM account_cart.favorite_variant WHERE owner_id=? ORDER BY sku COLLATE "C"
                """, String.class, owner);
        return new Snapshot(owner, Long.toString(version), List.copyOf(skus));
    }

    Decision prior(UUID owner, UUID intent, Change change, byte[] fingerprint) {
        record Receipt(String op, byte[] hash, boolean applied) {}
        var prior = db.query("""
                SELECT operation, payload_hash, applied FROM account_cart.favorite_receipt
                WHERE owner_id=? AND intent_id=?
                """, (r,n)->new Receipt(r.getString(1),r.getBytes(2),r.getBoolean(3)), owner,intent);
        if (prior.isEmpty()) return null;
        var receipt = prior.getFirst();
        if (!receipt.op().equals(change.operation().name()) || !MessageDigest.isEqual(receipt.hash(), fingerprint))
            throw new LocalCartCommands.Conflict();
        return new Decision(receipt.applied() ? snapshot(owner) : null);
    }

    boolean holds(UUID owner,String sku) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT FROM account_cart.favorite_variant WHERE owner_id=? AND sku=?)
                """, Boolean.class, owner,sku));
    }

    Decision apply(UUID owner, long generation, UUID intent, Change change, byte[] fingerprint, Set<String> published) {
        boolean removing = change.operation() == Operation.UNSET;
        var before = snapshot(owner);
        var effective = new TreeSet<>(change.skus());
        if (change.operation() == Operation.IMPORT) {
            // Guest selections predate this login. An explicit removal committed
            // since then wins, even when the first import was delayed or its item
            // wasn't on the account shelf yet. A later direct SET is never vetoed.
            var removed = db.queryForList("""
                    SELECT sku FROM account_cart.favorite_removal
                    WHERE owner_id=? AND login_generation=? AND sku=ANY(string_to_array(?,','))
                    """,String.class,owner,generation,String.join(",",change.skus()));
            effective.removeAll(removed);
        }
        boolean valid = published.containsAll(effective) || (removing && before.skus().contains(change.skus().getFirst()));
        if (!removing && valid) {
            var members = new TreeSet<>(before.skus());
            members.addAll(effective);
            valid = members.size() <= LIMIT;
        }
        if (valid) {
            db.update("INSERT INTO account_cart.favorite_shelf VALUES (?,0) ON CONFLICT DO NOTHING",owner);
            int changes;
            if (removing) {
                changes = db.update("DELETE FROM account_cart.favorite_variant WHERE owner_id=? AND sku=?",owner,change.skus().getFirst());
                db.update("""
                        INSERT INTO account_cart.favorite_removal(owner_id,sku,login_generation) VALUES (?,?,?)
                        ON CONFLICT(owner_id,sku) DO UPDATE SET login_generation=excluded.login_generation
                        """,owner,change.skus().getFirst(),generation);
            } else {
                changes = db.update("""
                        INSERT INTO account_cart.favorite_variant(owner_id,sku)
                        SELECT ?, unnest(string_to_array(?,',')) ON CONFLICT DO NOTHING
                        """,owner,String.join(",",effective));
            }
            if (changes > 0) db.update("UPDATE account_cart.favorite_shelf SET version=version+1 WHERE owner_id=?",owner);
        }
        db.update("INSERT INTO account_cart.favorite_receipt(owner_id,intent_id,operation,guest_intent,payload_hash,applied) VALUES (?,?,?,?,?,?)",
                owner,intent,change.operation().name(),change.operation()==Operation.IMPORT?intent:null,fingerprint,valid);
        return new Decision(valid ? snapshot(owner) : null);
    }
}
