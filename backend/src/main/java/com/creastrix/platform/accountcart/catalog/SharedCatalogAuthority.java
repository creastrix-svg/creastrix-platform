package com.creastrix.platform.accountcart.catalog;

import com.creastrix.platform.accountcart.LocalCartCommands.Conflict;
import com.creastrix.platform.accountcart.LocalCartCommands.Unavailable;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Looks up the storefront's actual merchandising data. A purchase writes only to the cart;
 * the separate catalog transaction takes shared locks, not a reservation or distributed commit. */
public final class SharedCatalogAuthority {
    public static final String ROLE="local_catalog_cart_reader";
    private static final String LOCAL_URL="jdbc:postgresql://127.0.0.1:55483/creastrix_local_catalog"
            +"?connectTimeout=2&socketTimeout=9&ApplicationName=creastrix-local-cart-catalog";
    // The two local fixtures describe one storefront. Never infer a seller from buyer input.
    private static final UUID LOCAL_MERCHANT=UUID.fromString("20000000-0000-4000-8000-000000000001");
    private final DataSource source;

    SharedCatalogAuthority(DataSource source) { this.source=Objects.requireNonNull(source); }

    public static SharedCatalogAuthority local(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Shared catalog requires its local lookup credential");
        }
        var adapter=new SharedCatalogAuthority(new DriverManagerDataSource(LOCAL_URL,ROLE,password));
        adapter.verifyProvisioning();
        return adapter;
    }

    /** Refuse an owner, inherited grants, or a reader with any direct seller-table access.
     * PostgreSQL grants a few harmless public routines; a second granted security-definer
     * routine or grant option on our lookup would broaden the declared capability. */
    void verifyProvisioning() {
        if(!Boolean.TRUE.equals(new JdbcTemplate(source).queryForObject("""
                SELECT current_database()='creastrix_local_catalog' AND current_user='local_catalog_cart_reader'
                   AND NOT (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolinherit OR r.rolbypassrls)
                   AND NOT EXISTS(SELECT FROM pg_auth_members WHERE member=r.oid)
                   AND has_schema_privilege(current_user,n.oid,'USAGE') AND NOT has_schema_privilege(current_user,n.oid,'CREATE')
                   AND (SELECT count(*)=3 FROM pg_constraint c WHERE c.convalidated AND c.contype='c' AND
                       (c.conrelid,c.conname::text) IN
                         ((to_regclass('local_catalog.product'),'product_id_fits_cart_protocol'),
                          (to_regclass('local_catalog.variant'),'variant_id_fits_cart_protocol'),
                          (to_regclass('local_catalog.variant'),'sku_fits_cart_protocol')))
                   AND (SELECT count(*)=3 FROM pg_trigger g WHERE NOT g.tgisinternal AND g.tgenabled IN ('O','A') AND
                       (g.tgrelid,g.tgname::text,g.tgfoid) IN
                         ((to_regclass('local_catalog.variant'),'retain_catalog_variant_identity',
                              to_regprocedure('local_catalog.retain_variant_identity()')),
                          (to_regclass('local_catalog.variant_identity'),'keep_catalog_variant_identity',
                              to_regprocedure('local_catalog.reject_identity_removal()')),
                          (to_regclass('local_catalog.variant_identity'),'keep_catalog_variant_identity_on_truncate',
                              to_regprocedure('local_catalog.reject_identity_removal()'))))
                   AND has_function_privilege(current_user,'local_catalog.cart_variants(text[])','EXECUTE')
                   AND NOT has_function_privilege(current_user,'local_catalog.cart_variants(text[])','EXECUTE WITH GRANT OPTION')
                   AND NOT EXISTS(SELECT FROM pg_proc p WHERE p.pronamespace=n.oid AND p.prosecdef
                       AND p.oid<>'local_catalog.cart_variants(text[])'::regprocedure AND has_function_privilege(current_user,p.oid,'EXECUTE'))
                   AND NOT EXISTS(SELECT FROM pg_class c WHERE c.relnamespace=n.oid AND c.relkind IN ('r','p','m','v','f')
                       AND (has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                            OR has_any_column_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,REFERENCES')))
                  FROM pg_roles r CROSS JOIN pg_namespace n WHERE r.rolname=current_user AND n.nspname='local_catalog'
                """,Boolean.class))) throw new IllegalStateException("Shared catalog requires permanent variant identity and a restricted lookup");
    }

    public record Target(String offerId,String variantId,String sku,String offerLabel,String variantLabel,String swatch,
                         String secondSwatch,String href,int stock,Long templateRevision,String configuration,
                         Long retainedRevision,String retainedConfiguration) {
        /** Historical cart FK anchors, never the source of current availability or prices. A
         * collision with an old mapping is not permission to reuse a SKU or move a Workspace. */
        public void remember(JdbcTemplate cart) {
            cart.update("INSERT INTO account_cart.offer(id,workspace_id,label) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    offerId,LOCAL_MERCHANT,offerLabel);
            if(!Boolean.TRUE.equals(cart.queryForObject("""
                    SELECT EXISTS (SELECT FROM account_cart.offer WHERE id=? AND workspace_id=?)
                    """,Boolean.class,offerId,LOCAL_MERCHANT))) throw new Conflict();
            cart.update("""
                    INSERT INTO account_cart.variant(offer_id,id,sku,label,cents,stock,swatch) VALUES(?,?,?,?,0,0,?)
                    ON CONFLICT DO NOTHING
                    """,offerId,variantId,sku,variantLabel,swatch);
            if(!Boolean.TRUE.equals(cart.queryForObject("""
                    SELECT EXISTS(SELECT FROM account_cart.variant WHERE offer_id=? AND id=? AND sku=?)
                    """,Boolean.class,offerId,variantId,sku))) throw new Conflict();
            cart.update("INSERT INTO account_cart.storefront_variant(sku) VALUES (?) ON CONFLICT DO NOTHING",sku);
            if(retainedRevision!=null) {
                cart.update("INSERT INTO account_cart.template_revision VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING",
                        offerId,retainedRevision,retainedConfiguration);
                if(!Boolean.TRUE.equals(cart.queryForObject("""
                        SELECT EXISTS(SELECT FROM account_cart.template_revision WHERE offer_id=? AND revision=? AND configuration=?::jsonb)
                        """,Boolean.class,offerId,retainedRevision,retainedConfiguration))) throw new Conflict();
            }
        }
    }

    public <T> T withVariant(String sku,Function<Optional<Target>,T> work) {
        return withVariants(List.of(sku),rows->work.apply(Optional.ofNullable(rows.get(sku))));
    }

    /** A distinct physical connection keeps a bounded SHARE lock on existing source rows
     * until the cart callback (including its local transaction commit) returns. At most
     * 1000 distinct SKUs in a single round trip. Never fall back to historic catalog rows
     * on permission, query or connectivity failure. A retry reconciles the cart receipt. */
    public <T> T withVariants(Collection<String> skus,Function<Map<String,Target>,T> work) {
        var ordered=new TreeSet<>(skus);
        if(ordered.size()>1000) throw new IllegalArgumentException("Catalog batch exceeds cart capacity");
        if(ordered.isEmpty()) return work.apply(Map.of());
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(var limits=connection.createStatement()) {
                    limits.execute("SET LOCAL lock_timeout='2s'");
                    limits.execute("SET LOCAL statement_timeout='8s'");
                }
                var rows=new HashMap<String,Target>();
                try(var query=connection.prepareStatement("SELECT * FROM local_catalog.cart_variants(?)")) {
                    var array=connection.createArrayOf("text",ordered.toArray(String[]::new));
                    try {
                        query.setArray(1,array);
                        try(var rs=query.executeQuery()) {
                            while(rs.next()) {
                                var target=new Target(rs.getString("offer_id"),rs.getString("variant_id"),rs.getString("sku"),
                                        rs.getString("offer_label"),rs.getString("variant_label"),rs.getString("swatch"),
                                        rs.getString("second_swatch"),rs.getString("href"),rs.getInt("stock"),
                                        rs.getObject("template_revision",Long.class),rs.getString("configuration"),
                                        rs.getObject("retained_revision",Long.class),rs.getString("retained_configuration"));
                                if(rows.put(target.sku(),target)!=null) throw new Unavailable();
                            }
                        }
                    } finally {array.free();}
                }
                return work.apply(Map.copyOf(rows));
            } finally {
                // Nothing in this transaction is written. Release locks even if the unrelated
                // cart operation failed; a lost transport is reported as unknown, not rejected.
                connection.rollback();
            }
        } catch(SQLException unavailable) { throw new Unavailable(); }
    }
}
