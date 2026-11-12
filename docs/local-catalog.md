# Connected local catalog

The connected storefront reads the full catalog from PostgreSQL, including
the objects shown on the homepage, in search and in recommendation rails.
All 39 items are local merchandise: buyers can open their product cards, save
a finish and add available units to the browser cart. Loop has four finishes
and a personalisation studio; the other 38 items have one finish each.
The page layout and existing product links stay the same. This is a local
read integration, not a public deployment.

## Start locally

Use a JDK 25, the frontend's pinned Node and npm, and native PostgreSQL server
tools. Run the following from the repository root in separate terminals:

```sh
bash backend/tools/local-catalog-db.sh start
bash backend/tools/local-catalog-db.sh run ./backend/mvnw -f backend/pom.xml -Dspring-boot.run.profiles=local-catalog spring-boot:run
```

```sh
cd frontend
npm ci
npm run dev:catalog
```

Open the [catalog](http://127.0.0.1:4174/catalog), [Loop](http://127.0.0.1:4174/products/loop)
or [Tide](http://127.0.0.1:4174/collections/objects/tide). The browser uses
the same origin for the catalog request. Spring listens separately on
`127.0.0.1:8081`. No Cognito login or existing developer database is needed.
For a built frontend rather than hot reload, use `npm run build:catalog` and
`npm run preview:catalog` instead of `npm run dev:catalog`. This build lives
in `dist-local-catalog/`; it does not replace the independent offline build.

The helper creates a private PostgreSQL cluster on loopback port 55483. It
retains its data outside Git between starts. Flyway migrations V1–V2 introduce
the first two offers; V3–V5 add presentation data, ratings, routes and rails.
V6–V7 give the 37 photographed objects a local price, stock, rating data and
complete recommendation rails without overwriting Loop/Tide prices or stock.
V8 distinguishes named collections from the seller's assortment and records
which products can serve the same purpose. V9 restricts offer links to the
three supported detail route families: `/products/:slug`, `/collections/objects/:slug`
and `/preview/items/:slug`. The same detail paths support reviews. Plain SKUs
cannot use `design-<number>`, which identifies personalisation lines in the cart.
Use `CREASTRIX_PG_BIN` if the PostgreSQL server binaries are not found.
Use `bash backend/tools/local-catalog-db.sh psql` to inspect or edit **only the
dedicated local catalog**, for example:

```sql
SELECT product_id, id, cents, previous_cents, stock
FROM local_catalog.variant
ORDER BY product_id, display_order;
```

Stop the frontend and backend with Ctrl+C, then run
`bash backend/tools/local-catalog-db.sh stop`. Stopping does not delete data.

## What belongs to the catalog

- **Product:** stable ID, name, existing detail URL, currency, and whether it
  offers the existing personalisation UI. Loop's four finishes are one result;
  Tide has one finish and no personalisation or 3D model.
- **Variant:** its product-relative ID, stable cart key, translated finish name,
  swatch, current and optional previous price in integer cents, available units,
  photo paths and optional model path. Tide and Loop both have a cobalt finish
  but do not share a cart key or stock.
- **Discovery:** all 39 seeded catalog entries have an identity, route and
  localised presentation. Categories and placements determine which entries
  appear on a shelf. Ordered recommendations reference these same entries;
  a linked object has one name and one rating wherever it appears.
- **More from this seller:** the compact rail below the purchase controls shows
  other offers from the single local seller, Creastrix. The server ranks the
  first four; these fill the first desktop view, not the whole carousel. On
  scroll the buyer can reach the remaining offers from the same loaded catalog,
  with collection members first, excluding the current product and the entire
  lower recommendation row. Without an explicit seller selection the server
  ranks other offers; if its response has no seller rail the UI adds none.
  For a named collection, the link
  opens its catalog filter (e.g. `/catalog?collection=loop`); otherwise it
  opens the seller's catalog. The filtered catalog includes the current product
  and does not mix in unrelated results when the collection ends.
- **“You may also like” — first:** after reserving the seller's opening cards, rank other
  offers by evidence in their descriptions. Names, collection IDs and a
  manually assigned matching product type are not required for similarity.
  This is a conservative text matcher: it normalises language and looks for
  several distinctive terms or equivalent expressions. A descriptive subtitle
  can support a match; a colour, material or promotion alone should not.
  When both products have useful English descriptions, that is the common
  comparison language; otherwise use a shared available translation.
  Other translations can reinforce a match, not outvote unrelated primary copy.
  It runs locally without sending seller text to an external service. It is
  not a pretrained embedding model: unfamiliar paraphrases can be missed.
- **Continuation in the same row:** append all remaining eligible offers in
  the source item's first specific catalog department, in its
  stored department order. Skip the general gift tag and use “Home & living”
  only when there is no more specific department. For instance, Drift's section
  is Desk; Loop's is Décor. The two ranking sources share a single heading
  and scroll area. Five is the desktop viewport capacity, not a limit on
  candidates. No product repeats, even past the first five, and the current
  product and the seller shelf stay excluded.
- **Ratings:** each item has a count and a nullable average on the 1–5 scale.
  The development seed supplies illustrative summaries (including unrated
  items), not reviews or purchases by real people. No text reviews, accounts
  or verified-purchase claims are generated. This slice reads summaries;
  it does not collect or publish buyer reviews.
- **Presentation:** React formats amounts and discounts. Until an operator supplies a
  cover, the existing bundled photos are used. A seller-supplied cover uses two
  server-generated images; private originals are never included in the feed.

The connected client reads one catalog snapshot on page load, so list and
detail agree on the price for the same variant. Reload to see a committed
database edit. The connected mode does not substitute the offline catalog on
failure. The offline mode remains available separately.
Existing URLs, including older `/preview/items/…` links, resolve to the same
catalog identities. Generic offers use the common product card; personalisation
or 3D requires actual configuration and assets, not just a new database row.

## Development data and eventual replacement

The catalog-only migrations bootstrap the private development database,
not a deployment database. The supplied items are marked with
`seed_origin = 'buyer-catalog-v1'`; new manually entered products have no
such marker by default. Before a public launch, provision a clean commerce
dataset without these development seeds, illustrative stock, prices or
ratings. Do not publish this profile, copy these ratings into live listings,
or erase account data. A clean browser origin also avoids treating an old
local cart as a real order. This development profile still checks the legacy
Loop/Tide card adapters and their known finish IDs at startup; decouple them
before replacing the entire feed. Deleting every seed today is not a supported
way to switch this build to a different catalog.

The endpoint is read-only. A displayed price is not a checkout quote; adding
an item to the existing browser cart does not write to the database or
reserve anything. The seeded product rows and their variants are **local catalog
data**, not a shortcut to exposing Ready-Made Product inventory, creating a
Listing, or accepting a manufacturing order. The [domain specifications](domain/README.md)
retain their existing approval status.

## Publish a local seller photo

Start the catalog and storefront as described above. From the repository root:

```sh
bash backend/tools/catalog-photo.sh compile
bash backend/tools/catalog-photo.sh seller loop cobalt
# For Tide, start another session: bash backend/tools/catalog-photo.sh seller tide cobalt
```

Open the private five-minute link printed in the terminal, choose one JPEG or PNG
and select **Publish local photo**. The page identifies the product and variant.
Use the link only on this computer; do not put it in an issue or share it in chat.
The operator acts for Creastrix using local OS access, not a buyer or marketplace
seller login. The storefront itself has no upload route. If the outcome of a
submission cannot be confirmed, check the current cover before starting another.

The server creates a thumbnail (long edge up to 480 px) and a larger display photo
(up to 1600 px). Both preserve proportions, honour supported orientation metadata
and never enlarge small images. The display files are JPEGs: transparency is
composited on white and source metadata is omitted. Originals must be no larger
than 12 MiB, 24 megapixels and 12,000 px on either side. For PNG use sRGB samples
without an embedded ICC profile (untagged samples are interpreted as sRGB). Supported
RGB JPEG profiles are converted on read. If an image is rejected, export an RGB
JPEG in sRGB; do not simply strip a wide-gamut or linear profile from unchanged pixels.
Animated and unsupported colour formats are refused without changing the cover.

Reload [the catalog](http://127.0.0.1:4174/catalog): the tile loads the thumbnail;
the product card and expanded gallery load the larger photo. Publication changes
only the cover of the chosen variant. Other finishes and gallery images stay in
place. Superseded cover URLs stop serving files; copies already downloaded by buyers
cannot be revoked.

The dedicated catalog state directory contains `product-media/originals` and
`product-media/derivatives`, both private. PostgreSQL selects which derivatives
buyers may retrieve; it never exposes an original's key. Keep these directories
outside every web root and back them up together with PostgreSQL. There is no
automatic deletion or third-party seller management.
