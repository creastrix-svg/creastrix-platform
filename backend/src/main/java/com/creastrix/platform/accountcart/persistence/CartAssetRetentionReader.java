package com.creastrix.platform.accountcart.persistence;

import java.util.Optional;
import java.util.UUID;

import com.creastrix.platform.accountcart.assets.CartAssetRetention.Facts;
import com.creastrix.platform.accountcart.assets.CartAssetRetention.PinHistory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal read-only snapshot, never authority to delete bytes. No web route or collector uses it. */
public final class CartAssetRetentionReader {
    private final JdbcTemplate sql;

    public CartAssetRetentionReader(JdbcTemplate sql) {
        this.sql=sql;
    }

    public Optional<Facts> facts(UUID owner, UUID asset) {
        return sql.query("""
            SELECT started_at,completed,ever_confirmed,ever_pinned,confirmed_count,pending_count,
                   unreferenced_observed_at,reconciliation_required
            FROM account_cart.durable_asset_retention_facts WHERE owner_id=? AND asset_id=?
            """, (r,n)->{
            var pinned=r.getObject("ever_pinned",Boolean.class);
            var last=r.getTimestamp("unreferenced_observed_at");
            return new Facts(r.getTimestamp("started_at").toInstant(),r.getBoolean("completed"),
                    r.getBoolean("ever_confirmed"), pinned==null ? PinHistory.UNKNOWN
                        : pinned ? PinHistory.PREVIOUSLY_PINNED : PinHistory.NEVER_PINNED,
                    r.getLong("confirmed_count"),r.getLong("pending_count"),
                    last==null ? null : last.toInstant(),r.getBoolean("reconciliation_required"));
        },owner,asset).stream().findFirst();
    }
}
