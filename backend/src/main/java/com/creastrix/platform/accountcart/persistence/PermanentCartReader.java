package com.creastrix.platform.accountcart.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** Receives the internal user exclusively from the admitted server session, never a request parameter. */
public final class PermanentCartReader {
    public static final int MAX_READ_LINES = 1000;
    public static final class ReadUnavailable extends RuntimeException {
        private ReadUnavailable() { super("CART_READ_LIMIT"); }
    }
    private final JdbcTemplate jdbc;

    public PermanentCartReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Line(UUID lineId, String offerLabel, String variantLabel, String designLabel, String quantity,
                       String offerId, String variantId, boolean listed, String templateRevision, String swatch, String secondSwatch) {}

    /** One PostgreSQL snapshot: absent/ineligible user is not an empty cart. This read never binds a session. */
    public Optional<List<Line>> lines(UUID owner) {
        return jdbc.query("""
            SELECT l.id, o.label AS offer_label, v.label AS variant_label, d.label AS design_label,
                   l.quantity,l.offer_id,l.variant_id,o.listed,d.template_revision,v.swatch,p.second_swatch
            FROM public.users u
            LEFT JOIN LATERAL (
                SELECT id,owner_id,offer_id,variant_id,design_id,design_revision,quantity,created_at
                FROM account_cart.cart_line WHERE owner_id=u.id ORDER BY created_at,id LIMIT ?
            ) l ON true
            LEFT JOIN account_cart.offer o ON o.id=l.offer_id
            LEFT JOIN account_cart.variant v ON (v.offer_id,v.id)=(l.offer_id,l.variant_id)
            LEFT JOIN account_cart.variant_presentation p ON (p.offer_id,p.variant_id)=(v.offer_id,v.id)
            LEFT JOIN account_cart.design_revision d ON (d.owner_id,d.design_id,d.revision)=
                (l.owner_id,l.design_id,l.design_revision)
            WHERE u.id=? AND u.status='ACTIVE' ORDER BY l.created_at,l.id
            """, (ResultSetExtractor<Optional<List<Line>>>) rs -> {
            if (!rs.next()) return Optional.empty();
            List<Line> result = new ArrayList<>();
            do {
                UUID id = rs.getObject("id", UUID.class);
                if (id != null) {
                    if (result.size()==MAX_READ_LINES) throw new ReadUnavailable();
                    result.add(new Line(id, rs.getString("offer_label"), rs.getString("variant_label"),
                            rs.getString("design_label"), Long.toString(rs.getLong("quantity")),
                            rs.getString("offer_id"),rs.getString("variant_id"),rs.getBoolean("listed"),
                            rs.getString("template_revision"),rs.getString("swatch"),rs.getString("second_swatch")));
                }
            } while (rs.next());
            return Optional.of(List.copyOf(result));
        }, MAX_READ_LINES + 1, owner);
    }
}
