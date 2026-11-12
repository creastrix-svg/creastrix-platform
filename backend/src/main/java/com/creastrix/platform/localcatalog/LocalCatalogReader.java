package com.creastrix.platform.localcatalog;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Reads presentation data, not a Listing publication, inventory reservation or quote. */
@Repository
@Profile("local-catalog")
class LocalCatalogReader {
    record Catalog(List<Product> products, List<Entry> entries, List<Recommendation> recommendations) {}
    record Product(String id, String name, String href, boolean personalizable, String currency,
                   int designFeeCents, List<Variant> variants) {}
    record Variant(String id, String sku, int cents, Integer previousCents, int stock,
                   List<String> colors, String checkTone, Map<String, String> name,
                   List<String> photos, String model) {}
    record Rating(int count, BigDecimal average) {}
    record Entry(String id, String kind, String name, String href, Map<String, String> title,
                 Map<String, String> material, Map<String, String> description, String photo,
                 String thumb, List<String> categories, Integer editionOrder, Integer homeOrder,
                 Rating rating, String collectionId, String collectionName, @JsonIgnore String substitutionKey) {}
    record Recommendation(String productId, String section, List<String> itemIds) {}

    private static final String READ_PRODUCTS = """
            SELECT p.id, p.name, p.href, p.personalizable, p.currency, p.design_fee_cents,
                   v.id AS variant_id, v.sku, v.cents, v.previous_cents, v.stock, v.colors,
                   v.check_tone, v.name AS localized_name,
                   CASE WHEN cover.detail_url IS NULL THEN v.photos
                        ELSE ARRAY[cover.detail_url] || ARRAY(SELECT f.path FROM unnest(v.photos)
                            WITH ORDINALITY AS f(path,position) WHERE f.position>1 ORDER BY f.position) END AS photos, v.model
              FROM local_catalog.product p
              JOIN local_catalog.variant v ON v.product_id = p.id
              JOIN local_catalog.item_presentation i ON i.product_id = p.id
              LEFT JOIN local_catalog.published_photo cover ON (cover.product_id,cover.variant_id)=(p.id,v.id)
             WHERE p.kind = 'offer'
             ORDER BY p.display_order, p.id, v.display_order, v.id
            """;
    // Offer thumbnails must follow the primary variant's current media, including
    // its missing-photo state; an older editorial photo must not replace it.
    private static final String READ_ENTRIES = """
            SELECT p.id, p.kind, p.name, p.href, p.rating_count, p.rating_average,
                   i.title, i.material, i.description, i.categories, i.edition_order, i.home_order,
                   i.collection_id, family.name AS collection_name, i.substitution_key,
                   CASE WHEN p.kind = 'offer' THEN cover.photo ELSE i.photo END AS photo,
                   CASE WHEN p.kind <> 'offer' THEN i.thumb
                        WHEN cover.thumb_url IS NOT NULL THEN cover.thumb_url
                        WHEN i.photo = cover.photo THEN coalesce(i.thumb, cover.photo)
                        ELSE cover.photo END AS thumb
              FROM local_catalog.product p
              JOIN local_catalog.item_presentation i ON i.product_id = p.id
              LEFT JOIN local_catalog.merchandise_collection family ON family.id = i.collection_id
              LEFT JOIN LATERAL (
                  SELECT v.id, coalesce(pub.detail_url,v.photos[array_lower(v.photos,1)]) AS photo, pub.thumb_url
                    FROM local_catalog.variant v
                    LEFT JOIN local_catalog.published_photo pub ON (pub.product_id,pub.variant_id)=(v.product_id,v.id)
                   WHERE v.product_id = p.id ORDER BY v.display_order, v.id LIMIT 1
              ) cover ON p.kind = 'offer'
             WHERE p.kind <> 'offer' OR cover.id IS NOT NULL
             ORDER BY p.display_order, p.id
            """;
    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    LocalCatalogReader(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Keep merchandising references, rating summaries, and variants from the same
     * committed snapshot even when local catalog data is edited during the read. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Catalog read() {
        List<Product> products = products();
        List<Entry> entries = jdbc.query(READ_ENTRIES, (row, index) -> new Entry(
                row.getString("id"), row.getString("kind"), row.getString("name"), row.getString("href"),
                labels(row, "title"), labels(row, "material"), labels(row, "description"),
                row.getString("photo"), row.getString("thumb"), strings(row, "categories"),
                row.getObject("edition_order", Integer.class), row.getObject("home_order", Integer.class),
                new Rating(row.getInt("rating_count"), row.getBigDecimal("rating_average")),
                row.getString("collection_id"), row.getString("collection_name"), row.getString("substitution_key")));
        var recommendations = jdbc.query("""
                SELECT product_id, section, array_agg(item_id ORDER BY position) AS item_ids
                  FROM local_catalog.recommendation WHERE section = 'studio'
                 GROUP BY product_id, section ORDER BY product_id, section
                """, (row, index) -> new Recommendation(row.getString("product_id"), row.getString("section"),
                strings(row, "item_ids")));
        return new Catalog(products, entries, LocalCatalogRecommendations.select(entries, recommendations));
    }

    private List<Product> products() {
        return jdbc.query(READ_PRODUCTS, rows -> {
            var products = new LinkedHashMap<String, Product>();
            while (rows.next()) {
                String id = rows.getString("id");
                Product product = products.get(id);
                if (product == null) {
                    product = new Product(id, rows.getString("name"), rows.getString("href"),
                            rows.getBoolean("personalizable"), rows.getString("currency"),
                            rows.getInt("design_fee_cents"), new ArrayList<>());
                    products.put(id, product);
                }
                product.variants().add(new Variant(rows.getString("variant_id"), rows.getString("sku"),
                        rows.getInt("cents"), rows.getObject("previous_cents", Integer.class), rows.getInt("stock"),
                        strings(rows, "colors"), rows.getString("check_tone"),
                        labels(rows, "localized_name"),
                        strings(rows, "photos"), rows.getString("model")));
            }
            return List.copyOf(products.values());
        });
    }

    private Map<String, String> labels(ResultSet row, String column) throws SQLException {
        return mapper.readValue(row.getString(column), LABELS);
    }

    private static List<String> strings(ResultSet rows, String column) throws SQLException {
        Array array = rows.getArray(column);
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
