-- Private references and public publication IDs have separate namespaces. Reserve all
-- three object keys before touching the filesystem so an interrupted upload is identifiable.
CREATE TABLE local_catalog.photo_upload (
    id uuid PRIMARY KEY,
    product_id text NOT NULL,
    variant_id text NOT NULL,
    original_key uuid NOT NULL UNIQUE,
    thumb_key uuid NOT NULL UNIQUE,
    detail_key uuid NOT NULL UNIQUE,
    started_at timestamptz NOT NULL DEFAULT current_timestamp,
    FOREIGN KEY (product_id, variant_id) REFERENCES local_catalog.variant(product_id,id),
    UNIQUE (product_id, variant_id, id),
    CHECK (thumb_key <> detail_key AND original_key NOT IN (thumb_key,detail_key)
           AND id NOT IN (original_key,thumb_key,detail_key))
);

-- A version exists only once all three immutable files have been written and verified.
-- Superseded versions are retained; only the cover pointer is a public read grant.
CREATE TABLE local_catalog.photo_version (
    id uuid PRIMARY KEY,
    product_id text NOT NULL,
    variant_id text NOT NULL,
    original_type text NOT NULL CHECK (original_type IN ('image/jpeg','image/png')),
    original_bytes integer NOT NULL CHECK (original_bytes BETWEEN 1 AND 12582912),
    original_sha text NOT NULL CHECK (original_sha ~ '^[0-9a-f]{64}$'),
    original_width integer NOT NULL CHECK (original_width > 0),
    original_height integer NOT NULL CHECK (original_height > 0),
    thumb_bytes integer NOT NULL CHECK (thumb_bytes BETWEEN 1 AND 16777216),
    thumb_sha text NOT NULL CHECK (thumb_sha ~ '^[0-9a-f]{64}$'),
    thumb_width integer NOT NULL CHECK (thumb_width BETWEEN 1 AND 480),
    thumb_height integer NOT NULL CHECK (thumb_height BETWEEN 1 AND 480),
    detail_bytes integer NOT NULL CHECK (detail_bytes BETWEEN 1 AND 16777216),
    detail_sha text NOT NULL CHECK (detail_sha ~ '^[0-9a-f]{64}$'),
    detail_width integer NOT NULL CHECK (detail_width BETWEEN 1 AND 1600),
    detail_height integer NOT NULL CHECK (detail_height BETWEEN 1 AND 1600),
    approved_at timestamptz NOT NULL DEFAULT current_timestamp,
    FOREIGN KEY (product_id,variant_id,id) REFERENCES local_catalog.photo_upload(product_id,variant_id,id),
    UNIQUE (product_id,variant_id,id)
);
CREATE TABLE local_catalog.photo_cover (
    product_id text NOT NULL,
    variant_id text NOT NULL,
    publication_id uuid NOT NULL UNIQUE,
    PRIMARY KEY (product_id,variant_id),
    FOREIGN KEY (product_id,variant_id,publication_id) REFERENCES local_catalog.photo_version(product_id,variant_id,id)
);
CREATE VIEW local_catalog.published_photo AS
    SELECT c.product_id,c.variant_id,c.publication_id,
           '/api/local-catalog/media/' || c.publication_id || '/thumb.jpg' AS thumb_url,
           '/api/local-catalog/media/' || c.publication_id || '/detail.jpg' AS detail_url
      FROM local_catalog.photo_cover c
      JOIN local_catalog.product p ON p.id=c.product_id AND p.kind='offer'
      JOIN local_catalog.item_presentation i ON i.product_id=p.id;
