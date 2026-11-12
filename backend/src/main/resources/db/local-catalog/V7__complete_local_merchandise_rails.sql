-- Fill only missing sections of the owner-approved local merchandise set.
-- Existing hand-picked rails, their ordering and the products are untouched.
-- The studio rail is a collection grouping here, not a claim about third-party
-- seller identity. Ranking is a one-time starting arrangement, not analytics.
WITH merchandise AS (
    SELECT p.id, p.display_order, i.categories,
           CASE WHEN p.id = 'loop' OR p.href LIKE '/preview/items/%'
                THEN 'loop-collection' ELSE 'object-collection' END AS collection
    FROM local_catalog.product p JOIN local_catalog.item_presentation i ON i.product_id = p.id
    WHERE p.kind = 'offer' AND p.seed_origin = 'buyer-catalog-v1'
), missing_sections AS (
    SELECT m.*, shape.section, shape.capacity
    FROM merchandise m
    CROSS JOIN (VALUES ('studio', 6), ('related', 5)) AS shape(section, capacity)
    WHERE NOT EXISTS (
        SELECT 1 FROM local_catalog.recommendation r
        WHERE r.product_id = m.id AND r.section = shape.section
    )
), ranked AS (
    SELECT source.id AS product_id, source.section, source.capacity, candidate.id AS item_id,
           row_number() OVER (
               PARTITION BY source.id, source.section
               ORDER BY overlap.total DESC, candidate.display_order, candidate.id
           ) AS rank
    FROM missing_sections source
    JOIN merchandise candidate ON candidate.id <> source.id
    CROSS JOIN LATERAL (
        SELECT count(*) AS total FROM unnest(source.categories) AS category(value)
        WHERE category.value = ANY(candidate.categories)
    ) AS overlap
    WHERE (source.section = 'studio' AND candidate.collection = source.collection)
       OR (source.section = 'related' AND overlap.total > 0)
)
INSERT INTO local_catalog.recommendation (product_id, section, item_id, position)
SELECT product_id, section, item_id, rank::integer - 1 FROM ranked WHERE rank <= capacity;
