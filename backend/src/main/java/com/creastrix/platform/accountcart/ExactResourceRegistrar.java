package com.creastrix.platform.accountcart;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore;
import com.creastrix.platform.accountcart.assets.PrivateCartAssetStore.Stored;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal pre-registration, not a web grant. The digest narrows candidate
 * classes; it never establishes equality. A removed last member leaves no
 * bytes or unowned representative behind. A live missing representative is
 * unresolved, not permission to invent a new identity. */
final class ExactResourceRegistrar {
    private static final int MAX_CANDIDATES = 8;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final PrivateCartAssetStore files;
    private record Member(UUID asset, Stored stored, Long equivalence) {}

    ExactResourceRegistrar(DataSource data, PrivateCartAssetStore files) {
        db = new JdbcTemplate(data);
        db.setQueryTimeout(5);
        this.files = Objects.requireNonNull(files);
        tx = new TransactionTemplate(new DataSourceTransactionManager(data));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(8);
    }

    long register(PreparedCartImportStore.Admission admission, UUID asset) throws IOException {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("CART_RESOURCE_REQUIRES_NO_AMBIENT_TRANSACTION");
        UUID owner = admission.owner();
        checkAdmission(admission,false);
        Member target = target(owner,asset,false);
        List<Member> before = candidates(owner,target);
        byte[] bytes = files.read(target.stored);
        Long match = null;
        for (var candidate : before) {
            if (Arrays.equals(bytes,files.read(candidate.stored))) {
                if (match!=null) throw new IllegalStateException("CART_RESOURCE_CLASS_AMBIGUOUS");
                match = candidate.equivalence;
            }
        }
        Long verified = match;
        return Objects.requireNonNull(tx.execute(status -> {
            db.execute("SET LOCAL lock_timeout='3s'");
            checkAdmission(admission,true);
            // Re-read after the owner lock. If another registrar published a
            // candidate or removed the compared object, restart outside locks.
            if (!target.equals(target(owner,asset,true)) || !before.equals(candidates(owner,target)))
                throw new IllegalStateException("CART_RESOURCE_REGISTRATION_CHANGED");
            if (target.equivalence != null) {
                if (!target.equivalence.equals(verified)) throw new IllegalStateException("CART_RESOURCE_CLASS_MISMATCH");
                return verified;
            }
            Long number = verified != null ? verified :
                    db.queryForObject("SELECT nextval('account_cart.verified_resource_number')", Long.class);
            db.update("INSERT INTO account_cart.verified_resource(owner_id,asset_id,comparison_version,class_number) VALUES (?,?,1,?)",
                    owner,asset,number);
            return number;
        }));
    }

    private void checkAdmission(PreparedCartImportStore.Admission a, boolean lock) {
        String fence = lock ? " FOR NO KEY UPDATE" : "";
        if (db.queryForList("SELECT 1 FROM account_cart.write_scope WHERE id=? AND generation=? AND owner_id=?" + fence,
                Integer.class,a.scope(),a.generation(),a.owner()).isEmpty() ||
                db.queryForList("SELECT 1 FROM public.users WHERE id=? AND status='ACTIVE' AND account_eligibility_generation=?"
                        + fence,Integer.class,a.owner(),a.eligibilityGeneration()).isEmpty())
            throw new IllegalStateException("CART_ADMISSION_REFUSED");
    }

    private static final String FIELDS =
            "a.asset_id,a.object_key,a.kind,a.byte_count,a.sha256,m.class_number ";
    private static final String MEMBERS =
            " FROM account_cart.asset a LEFT JOIN account_cart.verified_resource m ON " +
            "(m.owner_id,m.asset_id)=(a.owner_id,a.asset_id) AND m.comparison_version=1 ";

    private Member target(UUID owner, UUID asset, boolean lock) {
        var rows=db.query("SELECT "+FIELDS+MEMBERS+"WHERE a.owner_id=? AND a.asset_id=? AND a.completed" +
                (lock ? " FOR SHARE OF a" : ""),ExactResourceRegistrar::member,owner,asset);
        if (rows.isEmpty()) throw new IllegalStateException("CART_UNRESOLVED_ASSET");
        return rows.getFirst();
    }

    private List<Member> candidates(UUID owner, Member target) {
        var ref=target.stored;
        var rows=db.query("SELECT DISTINCT ON (m.class_number) "+FIELDS+MEMBERS+
                "WHERE a.owner_id=? AND a.completed AND a.kind=? AND a.byte_count=? AND a.sha256=? "+
                "AND m.class_number IS NOT NULL ORDER BY m.class_number,a.asset_id LIMIT ?",
                ExactResourceRegistrar::member,owner,ref.kind().name(),ref.byteCount(),ref.sha256(),MAX_CANDIDATES+1);
        if(rows.size()>MAX_CANDIDATES) throw new IllegalStateException("CART_RESOURCE_CANDIDATE_LIMIT");
        return rows;
    }

    private static Member member(ResultSet r,int ignored) throws SQLException {
        var ref=new Stored(r.getObject("object_key",UUID.class),PrivateCartAssetStore.Kind.valueOf(r.getString("kind")),
                Math.toIntExact(r.getLong("byte_count")),r.getString("sha256"));
        return new Member(r.getObject("asset_id",UUID.class),ref,r.getObject("class_number",Long.class));
    }
}
