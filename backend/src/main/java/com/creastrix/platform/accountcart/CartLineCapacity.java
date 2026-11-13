package com.creastrix.platform.accountcart;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.creastrix.platform.accountcart.persistence.PermanentCartReader;

/** A departed page cannot dismiss its undo rows reliably. Under the existing
 * owner/cart transaction lock, reclaim just enough removed slots for the new
 * purchase. GET, replay and rejected additions never evict anything. */
final class CartLineCapacity {
    private CartLineCapacity() {}
    static boolean makeRoom(JdbcTemplate db, UUID owner, GuestCartMergePlanner.Plan plan) {
        long growth=plan.insertions().size()-plan.replacements().stream().mapToLong(r->r.redundantIds().size()).sum();
        long required=db.queryForObject("SELECT count(*) FROM account_cart.cart_line WHERE owner_id=?",Long.class,owner)
                +growth-PermanentCartReader.MAX_READ_LINES;
        if(required<=0)return true;
        // If there are fewer than 'required' undo snapshots the caller rolls
        // back the whole transaction. Never sacrifice a currently active row.
        return db.update("""
                DELETE FROM account_cart.cart_line WHERE owner_id=? AND removed AND id IN
                  (SELECT id FROM account_cart.cart_line WHERE owner_id=? AND removed ORDER BY created_at,id LIMIT ?)
                """,owner,owner,required)==required;
    }
}
