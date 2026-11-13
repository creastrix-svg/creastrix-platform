-- Cart, guest-transfer and studio protocols accept at most 200 ASCII slug characters.
-- Fail visibly on incompatible existing data; never truncate or reassign a stable SKU.
ALTER TABLE local_catalog.variant ADD CONSTRAINT sku_fits_cart_protocol CHECK (length(sku) <= 200);
