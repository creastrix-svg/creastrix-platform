-- The two generic design routes look up their item by the final path segment.
-- Reject mismatched paths at write-time rather than breaking the entire read
-- snapshot or leading a buyer from one tile to an unrelated detail page.
ALTER TABLE local_catalog.product ADD CONSTRAINT design_route_matches_item CHECK (
    kind = 'offer'
    OR (kind = 'edition' AND href = '/collections/objects/' || id)
    OR (kind = 'concept' AND href = '/preview/items/' || id)
);

-- Both endpoints of a rail belong to the buyer-facing presentation inventory.
ALTER TABLE local_catalog.recommendation ADD CONSTRAINT recommendation_owner_is_presented
    FOREIGN KEY (product_id) REFERENCES local_catalog.item_presentation (product_id);
