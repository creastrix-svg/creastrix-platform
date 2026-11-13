-- A first delivery of an earlier guest choice must not undo a buyer's explicit
-- removal in the same login. Keep one marker per owner/SKU, not the guest list.
-- Generations are never reused; a fresh login may intentionally save it again.
CREATE TABLE account_cart.favorite_removal (
    owner_id uuid NOT NULL REFERENCES account_cart.favorite_shelf,
    sku text NOT NULL CHECK (length(sku) BETWEEN 1 AND 200 AND sku ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    login_generation bigint NOT NULL CHECK (login_generation > 0),
    PRIMARY KEY(owner_id, sku)
);
