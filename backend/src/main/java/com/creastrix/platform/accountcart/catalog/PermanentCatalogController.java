package com.creastrix.platform.accountcart.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.creastrix.platform.accountcart.StudioDesign;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public merchandising and compatible template revisions, not workspace authority,
 * write credentials or private buyer assets. No browser name is an offer identity. */
@RestController
@Profile("account-cart-local")
@ConditionalOnProperty(name="creastrix.test.shared-catalog", havingValue="false", matchIfMissing=true)
final class PermanentCatalogController {
    private final JdbcTemplate jdbc;
    PermanentCatalogController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Variant(String id, String sku, String label, int cents, int stock, String swatch,
                   List<String> photos, String model, String secondSwatch, String checkTone, Integer previousCents,
                   String retainedStudioRevision, boolean directAddSupported) {}
    record Offer(String id, String label, String currency, String templateRevision, String studioFormat,
                 int studioFeeCents, List<Variant> variants) {}

    @GetMapping("/api/account-catalog/products")
    ResponseEntity<List<Offer>> products() {
        var offers = new LinkedHashMap<String, Offer>();
        jdbc.query("""
                SELECT o.id, o.label, o.currency, o.studio_fee_cents, v.id AS variant_id,
                       v.sku, v.label AS variant_label, v.cents, v.stock, v.swatch,
                       p.photos, p.model, p.second_swatch, coalesce(p.check_tone,'dark') AS check_tone,
                       p.previous_cents, t.revision, retained.revision AS retained_studio_revision,
                       EXISTS (SELECT FROM account_cart.direct_add_variant WHERE sku=v.sku) AS direct_add_supported,
                       CASE WHEN o.id='loop' AND t.revision=2 AND t.configuration = ?::jsonb
                           THEN 'loop-studio-v1' END AS studio_format
                  FROM account_cart.offer o JOIN account_cart.variant v ON v.offer_id = o.id
                  LEFT JOIN account_cart.variant_presentation p ON (p.offer_id,p.variant_id)=(v.offer_id,v.id)
                  LEFT JOIN account_cart.template_revision retained
                    ON retained.offer_id=o.id AND o.id='loop' AND retained.revision=2 AND v.id=v.sku
                    AND retained.configuration = ?::jsonb
                    AND (retained.configuration->'variants') @> jsonb_build_array(v.id)
                  LEFT JOIN LATERAL (SELECT revision,configuration FROM account_cart.template_revision
                    WHERE offer_id=o.id ORDER BY revision DESC LIMIT 1) t ON true
                 WHERE o.listed ORDER BY o.id, v.id LIMIT 1001
                """, (org.springframework.jdbc.core.ResultSetExtractor<Void>) rows -> {
            int count = 0;
            while (rows.next()) {
                if (++count > 1000) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
                String id = rows.getString("id"), label = rows.getString("label"), currency = rows.getString("currency");
                String revision = rows.getString("revision"), format = rows.getString("studio_format");
                int fee = rows.getInt("studio_fee_cents");
                var offer = offers.computeIfAbsent(id, k -> new Offer(k, label, currency, revision, format, fee, new ArrayList<>()));
                var photos = rows.getArray("photos");
                offer.variants().add(new Variant(rows.getString("variant_id"), rows.getString("sku"), rows.getString("variant_label"),
                        rows.getInt("cents"), rows.getInt("stock"), rows.getString("swatch"),
                        photos == null ? List.of() : List.of((String[]) photos.getArray()), rows.getString("model"),
                        rows.getString("second_swatch"), rows.getString("check_tone"), (Integer) rows.getObject("previous_cents"),
                        rows.getString("retained_studio_revision"),rows.getBoolean("direct_add_supported")));
            }
            return null;
        }, StudioDesign.TEMPLATE_V2, StudioDesign.TEMPLATE_V2);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(List.copyOf(offers.values()));
    }
}
