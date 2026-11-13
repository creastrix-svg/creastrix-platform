package com.creastrix.platform.accountcart.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.creastrix.platform.accountcart.catalog.SharedCatalogAuthority;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Receives the internal user exclusively from the admitted server session, never a request parameter. */
public final class PermanentCartReader {
    public static final int MAX_READ_LINES = 1000;
    public static final class ReadUnavailable extends RuntimeException {
        private ReadUnavailable() { super("CART_READ_LIMIT"); }
    }
    private final JdbcTemplate jdbc;
    private final SharedCatalogAuthority source;

    public PermanentCartReader(JdbcTemplate jdbc) {
        this(jdbc,null);
    }
    public PermanentCartReader(JdbcTemplate jdbc, SharedCatalogAuthority source) {
        this.jdbc = jdbc; this.source = source;
    }

    public record Line(UUID lineId, String offerLabel, String variantLabel, String designLabel, String quantity, boolean removed,
                       String offerId, String variantId, boolean listed, String templateRevision, String swatch, String secondSwatch,
                       String sku, UUID designId, String designRevision, String studioFormat,String href) {
        Line withCurrent(SharedCatalogAuthority.Target t) {
            boolean linked=t!=null && offerId.equals(t.offerId()) && variantId.equals(t.variantId());
            return new Line(lineId,linked?t.offerLabel():offerLabel,linked?t.variantLabel():variantLabel,designLabel,
                    quantity,removed,offerId,variantId,linked,templateRevision,linked?t.swatch():swatch,
                    linked?t.secondSwatch():secondSwatch,sku,designId,designRevision,studioFormat,linked?t.href():null);
        }
    }

    public record Snapshot(long version,List<Line> lines) {}
    public Optional<List<Line>> lines(UUID owner) {
        return snapshot(owner).map(Snapshot::lines);
    }
    /** Cart version and line states come from the same PostgreSQL snapshot. An absent
     * or ineligible user is not an empty cart. Reading never creates or binds one. */
    public Optional<Snapshot> snapshot(UUID owner) {
        var local=jdbc.query("""
            SELECT l.id, o.label AS offer_label, v.label AS variant_label, d.label AS design_label,
                   coalesce(c.version,0) AS version,l.removed,l.quantity,l.offer_id,l.variant_id,o.listed,d.template_revision,v.swatch,p.second_swatch,
                   v.sku,l.design_id,l.design_revision,
                   CASE WHEN d.layout_format=1 AND account_cart.studio_v2(t.configuration,t.revision,v.id)
                        THEN 'surface-print-v1' END AS studio_format
            FROM public.users u
            LEFT JOIN account_cart.cart c ON c.owner_id=u.id
            LEFT JOIN LATERAL (
                SELECT id,owner_id,offer_id,variant_id,design_id,design_revision,quantity,created_at,removed
                FROM account_cart.cart_line WHERE owner_id=u.id ORDER BY created_at,id LIMIT ?
            ) l ON true
            LEFT JOIN account_cart.offer o ON o.id=l.offer_id
            LEFT JOIN account_cart.variant v ON (v.offer_id,v.id)=(l.offer_id,l.variant_id)
            LEFT JOIN account_cart.variant_presentation p ON (p.offer_id,p.variant_id)=(v.offer_id,v.id)
            LEFT JOIN account_cart.design_revision d ON (d.owner_id,d.design_id,d.revision)=
                (l.owner_id,l.design_id,l.design_revision)
            LEFT JOIN account_cart.template_revision t ON (t.offer_id,t.revision)=(d.offer_id,d.template_revision)
            WHERE u.id=? AND u.status='ACTIVE' ORDER BY l.created_at,l.id
            """, (ResultSetExtractor<Optional<Snapshot>>) rs -> {
            if (!rs.next()) return Optional.empty();
            long version=rs.getLong("version");
            List<Line> result = new ArrayList<>();
            do {
                UUID id = rs.getObject("id", UUID.class);
                if (id != null) {
                    if (result.size()==MAX_READ_LINES) throw new ReadUnavailable();
                    result.add(new Line(id, rs.getString("offer_label"), rs.getString("variant_label"),
                            rs.getString("design_label"), Long.toString(rs.getLong("quantity")),rs.getBoolean("removed"),
                            rs.getString("offer_id"),rs.getString("variant_id"),rs.getBoolean("listed"),
                            rs.getString("template_revision"),rs.getString("swatch"),rs.getString("second_swatch"),
                            rs.getString("sku"),rs.getObject("design_id",UUID.class),rs.getString("design_revision"),
                            rs.getString("studio_format"),null));
                }
            } while (rs.next());
            return Optional.of(new Snapshot(version,List.copyOf(result)));
        }, MAX_READ_LINES + 1, owner);
        if(source==null)return local;
        // One source snapshot. A changed/missing catalog tuple cannot accidentally relink a
        // retired item. The controller checks the original buyer admission again after this read.
        return local.map(cart->source.withVariants(cart.lines().stream().map(Line::sku).toList(),targets->
                new Snapshot(cart.version(),cart.lines().stream().map(line->line.withCurrent(targets.get(line.sku()))).toList())));
    }
}
