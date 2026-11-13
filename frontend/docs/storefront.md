# Buyer storefront

The following describes the default offline React storefront, including its
sample prices and stock. The [connected local catalog](../../docs/local-catalog.md)
reads merchandise, prices, rating summaries and recommendation links from
PostgreSQL on page load. Its 39 local products, including the photographed
objects labelled as concepts in the offline mode, open ordinary product cards.
For setup and the code map, see the [frontend guide](../README.md).

## Buyer homepage and catalog (local preview)

Open `/` to explore the buyer storefront without signing in. It shares search,
saved products, cart, language and appearance controls with the catalog and
product pages. Account access is still available from the header. The homepage
is readable while local drafts and cart contents are being restored; the
mini-cart does not offer edits against a cart that has not been restored.

The wide campaign opens the complete catalog. The carved-pattern banner
opens `/catalog?group=custom-or-made`: personalised items, made-to-order goods
**or** production files. It does not open an editor until a product is chosen.
Three category entrances below it show a closed gift box with a denim-blue ribbon,
embroidery in progress, and a carved figure holding an amber sphere beside its
sketch and drafting tools. They open **Ready-made**, **To personalise** and
**Design concepts** respectively. The two desktop banners use the same three
column tracks as the entrances: the catalog spans the first two, and the
customisation banner occupies the third. Phones stack the banners.
See the [image sources and credits](../public/shop-edition/process-photo-credits.txt)
for the packaging and design-concept artwork.

Popular picks can be narrowed to gifts, home, accessories, desk, kitchen, or
decor. The category strip below the heading docks underneath search; its catalog link opens that same category;
with **All** selected it opens the full catalog. The large campaign above always
opens the full catalog. These selections are not sales analytics.
The default shelf shows Loop, the Tide espresso cup, and 30 individual design previews: eight rows
of four on a wide screen. Thumbnails load lazily; the smaller grids keep the same
items on tablets and phones. Designs have individual detail URLs, localised
descriptions and two WebP sizes. Each tile links directly to the product or
design detail, without a separate quick-view button. Concepts show neither
prices nor fictional stock. Loop uses its existing variant pricing. Tide is a
single ready-made local offer at its existing URL, `/collections/objects/tide`.
The shelf, catalog, search, product and cart use the same demo price (€18); the demo
stock limit is 18 cups. **Loop and Tide use the same product card.** A single
finish still has a selected swatch. If the product has no model, the 3D control
remains visible but disabled; it never opens another product's model. The card
always includes the seller, three feature tiles,
specifications and expandable description. Tide has one photograph and no
personalisation; its three tiles sit directly below the gallery, including on
phones. No placeholder replaces the studio entry. The local cart and saved products can
contain both Tide and Loop; no inventory, payment or order request is sent.

Open `/catalog` (or its older alias `/preview/collection`) to browse products
and individual design concepts. **Ready-made** describes what the buyer can
buy; **To personalise** describes an optional feature. Loop belongs to both:
buyers can add it as-is or choose to personalise it. It appears once in the
results, even when both filters apply or several finishes match. With the
sale filter active, its card opens the discounted Chalk finish. Tide belongs
to Ready-made, not To personalise or Design concepts. Favourites retain
their existing variant identity. The editorial collection is not itself a
catalog item. Concept-only items open previews with no purchasing controls.

The **Menu** button opens a left-side drawer for the whole collection or a
specific selection. Its short slide and backdrop fade respect reduced motion.
Filters distinguish the offer (ready-made, made to order, production file, or
design concept) from the making technique and the buyer's personalisation
options. Colours can be combined: a match can satisfy any checked option in a
group, but must satisfy every active group. Made-to-order items and production
files currently have no matches; a concept is neither a file nor a promise
to manufacture. The cards show fixture details and the actual empty review
state, not generated ratings or popularity.

Filter selections and sorting live in the URL (`format`, `custom`,
`technique`, `color`, `sale`, `category`, `group`, `collection`, `sort`). Browser Back and the
product breadcrumb return to the previous selection. The page can sort by
displayed base price, name or available quantity. This is a local React storefront:
no catalog API, published listings, or checkout integration is included.

## Product pages and media

The product UI first shipped at `/products/loop`. It shares the storefront's system/light/dark appearance and native
DE/EN/RU/PL/ES/FR/IT language selector. Storefront language stays in public
document-memory state; it never expands or rewrites the authentication DE/EN
preference or its storage. It translates the owner's final product mockup,
not the Lovable redesign or an embedded HTML/iframe. The original external HTML
is unchanged. The new footer, native smooth rails and main-photo thumbnails are
explicit owner-approved exceptions to that reference. The shared footer leads to
store sections, not individual products or their studios.

The opaque theme-aware header is sticky, with a compact two-row mobile layout.
Its measured height supplies local anchor/focus clearance; resize does not scroll
the page. Search/cart popups and the icon menu remain below the modal layer.

Loop uses the [cobalt hero photo](../public/product/reference-asset-01.webp) as its
shape reference. The [shared profile](../src/product/studio/preview/loopShape.json) defines the
foot, curved sides and open rim for both the four colour-specific product models
(`loop-shaped-*.glb`) and the [2D studio sketch](../src/product/studio/preview/loopSketchProjection.ts).
Cobalt keeps its three original photos; coral, chalk and cobalt/chalk each have
one illustrative AI image. The legacy `loop.glb` is not used. The profile is a
visual approximation from the photo, not measured CAD or fabrication geometry.
Photo/3D share a viewport-centred keyboard modal, with focus
restoration and background inertness. `@google/model-viewer` 4.0.0 is a pinned,
lazy renderer loaded only on 3D selection, with an explicit loading/error fallback.
No CDN, external model/environment image, AR or backend work is added. The 3D
chunk is separate from ordinary storefront and authentication code. Expanded
view adds public-API quaternion arcball rotation, roll, pinch/twist, bounded zoom,
reset and keyboard controls only after actual load. Inline has bounded orbit
controls and a reset to the normal product view. Fullscreen captures the actual
numeric inline orbit/target and opens from that angle with extra framing room.
Closing restores the original inline view and its bounds unless Reset/Home was
used: reset centres the model at its normal product angle and zoom for each
viewport. Further free rotation in the dialog does not change the card's view.
One viewer survives host/language changes for the same source. Colour changes
retain the selected Photo/3D intent while a keyed gallery resets source-owned
photo index, errors, loading and viewer pose. 3D loads that colour's own model,
ignores old load/error events and offers explicit Photos recovery if unavailable,
without silently switching modes. Controls clean up pointer capture,
listeners and queued frames before restoring inline. The reference's 4.0.0 renderer
avoids the observed 4.4.0 non-AR orientation-update regression without a private
patch; this older renderer/Three peer choice still requires real browser/WebGL
verification and does not establish general dependency security.
Inline instructions stay available through `aria-describedby` without covering
the model; fullscreen retains its visible rotation guidance.
Main photos, thumbnails, gallery images and model import/load show only natural
pending spinners, with cached-ready handling and bounded terminal fallbacks.
Only media actions requiring the pending asset are disabled; close, navigation,
colour selection and unrelated cart/theme controls remain usable. Photo failures
and explicit retries belong to the exact variant/source, not neighbouring images.
The thumbnail strip retains its height for zero, one or three photos; missing or
failed assets use noninteractive placeholders, and a single photo has no arrows.

Public Redux sample cart/favorite state is unrelated to credentials, account
identity or private authentication state. The [local studio adapter](personalisation.md)
persists active cart lines; saved colours use independent local keys. A separate version-1 bounded
`creastrix.product.loop.preferences` record remembers only selected colour,
per-colour quantity to add next, Photo/3D intent and description disclosure across
refresh. A cart visit leaves an unsubmitted quantity alone. A successful add resets
only that colour's picker to one (zero and disabled when exhausted). The product
page separately shows the active cart quantity matching its colour and applied
design; stock is shared across all designs of that colour. An explicit valid URL
colour wins; invalid/duplicate colour queries use cobalt. Unknown versions/fields,
malformed or oversized records fall back safely;
quantities are safe integers clamped to each sample stock. A cart-exhausted zero
restores as one before cart hydration reconciles the remaining capacity. Denied/full storage keeps in-memory use.
No account, authentication, cart, favorite, locale or appearance value is persisted
by this adapter; existing locale/theme behavior and auth lifecycle are unchanged.
Preferences are local to this browser origin, not account synchronization or
cross-tab ordering. The native description summary switches localized Show more /
Show less with the saved open state; both labels reserve one shared width at
weight 600, hover only underlines and keyboard focus remains visible.
The owner requires future
authenticated cart/favorites to be stored by the backend for the internal User;
that needs a separate identity/target slice and is not delivered here. Browser
preference storage is not a substitute; guest-cart merge is not simulated.
Quantity is whole-digit input,
bounded by each sample stock (24/8/15/6), not nine; cart totals use integer cents.
Sample variants have one or two swatch colours with an exact-half diagonal split.
The product picker uses a vector circle for a smooth contour; cart fallbacks use
the same colours in a diagonal background. The cobalt/chalk combination has its
own demo identity, €28 price and six-unit stock; no seller editor or catalog API
is implemented. Five separate star buttons open a demo review form with the
chosen 1–5 rating and validated variant; the review text remains a separate link
to the list. Both routes provide a concrete return to that product/variant.
The form has an editable rating and text; invalid/missing ratings remain unselected.
It publishes nothing and submits no request or persistent data. Star-prefix hover
is decoration, not a submitted or stored rating; quantity and swatch feedback do
not resize controls.
The heart saves a product in its selected colour, not a standalone colour or a
personalised design. Saved products lists each marked variant
with its own photo or swatch, current sample price and direct variant link.
Each variant has its own localStorage key. Loop keeps its existing
`creastrix.product.loop.favorites.v1.` keys; Tide uses `creastrix.product.tide.favorites.v1.cobalt`:
saving writes a one-character marker and removing deletes only that key. Tabs
cannot overwrite other colours by writing an outdated list. No design,
account identifier, cart or price is stored in favorites. Storage failure
leaves the current view usable, without guaranteeing recovery after reload.
The icon-only appearance popup uses menu-radio keyboard navigation and localized
accessible names, without changing authentication appearance. Chalk's sample
€24.00-to-€19.90 comparison produces a checked, rounded 17% demo tag; all totals
and saved prices use current integer cents. This is not live offer/pricing logic.
Refresh restores a valid local cart through the [studio persistence adapter](personalisation.md).
In the connected catalog, an unlisted SKU is kept separately until the buyer
removes it or the offer returns. It contributes neither a price nor any orderable
units. Other cart lines and studio drafts still restore normally.
Header hover/focus preview supports editing
and removal. Its bounded last-five item list alone scrolls; header, total and
View cart remain outside that scroll area, including short viewports.
Click/touch opens `/preview/cart`. The small `/preview/*` routes and
`/products/loop/reviews` are demo/info views, not a marketplace API or real reviews.

CART-UI-001 provides a separate responsive cart view with variant-owned media,
current unit prices (including the existing Chalk comparison), numeric sample
stock, line subtotals and distinct product-line/unit counts. Continue shopping
replaces the cart's top Back link and keeps the existing product-return preference
and scroll contract, including a visit to the checkout prototype. Shared memory-only presentation slots
keep deleted rows in place, with disabled zero quantity/subtotal and a readable
muted appearance only during the current visit to the cart. Refreshing or leaving
and returning clears those undo slots, not the active items. Each removed row
has an independent, one-use Restore for its previous
quantity; unrelated edits/removals do not invalidate it. Explicit re-add of the
same variant uses the new quantity and retires only its old restore token, without
duplicates, merging or exceeding stock. Removed rows never enter active totals,
badges or the minicart, including when all rows are removed. Fixed-width cart
counters do not grow with colour labels. Unit price, quantity and line subtotal
share three aligned columns, also below the product heading on phones; sale prices
stay within the unit-price column. Cart limit notices expand around the centre
counter without widening the page. Full-cart and minicart quantity buttons include
the variant in their accessible names. The same keyed Remove/Restore action
retains focus without scrolling, with hidden live status and no top Undo area.
Only removed rows offer a separate X to discard their presentation slot and
Restore snapshot. Active quantities/totals and other snapshots are unaffected;
a later explicit add creates a new row. Focus moves to the nearest surviving
row action or Continue shopping, without scrolling. This never deletes a product
from the catalogue or an account.
When the summary moves below the items, it stays right-aligned and at most 360px
wide, shrinking to fit phones rather than stretching its Checkout link across a tablet.
The summary's Checkout link opens `/preview/checkout`; it is disabled when only
removed rows remain. Direct access with an empty active cart offers a return to
the cart instead of a form. The checkout prototype shows the active selection,
current item subtotal and a local contact/address preview. Use fictitious details:
form state disappears on departure or when the active cart becomes empty. Nothing
is submitted, persisted or placed into Redux. Required/email browser validation is
only presentation validation, not address verification. Shipping, tax calculation,
orders and payment are not implemented; the subtotal is not a final payable total.
No timers or artificial loading are added. Account persistence, guest merge and
backend cart/favorite APIs remain outside this interface-only slice.

Below the purchase controls, “More from this seller” prioritises other items in
the same named collection. Four tiles fit in the desktop grid; additional
items can be reached by scrolling or using the arrows. Its collection link opens that filtered catalog;
if the product has no collection, the seller link opens the single-seller catalog.
Below the description, “You may also like” is one horizontal list. Matches
from the seller's descriptive text come first, immediately followed by the
remaining offers from the same department. Up to five cards fit side by side
on desktop; scrolling reveals additional cards in the same list. The current
product and seller-shelf items are excluded. Short lists are centred, and an
empty list shows no heading or controls.
The offline preview has only its configured examples, not the live matcher. The rails use native
horizontal scrolling with snap/swipe and edge controls; motion requests are
instant under reduced motion.

Storefront routes retain the shell DOM and use React Router links. One feature-owned
focus/scroll hook covers path and query navigation and bounded document-memory
Back/Forward positions; the original auth shell does not also scroll these routes.
Titles/descriptions follow the current route/language. The nonpublic pilot is
`noindex,nofollow`, with no fabricated canonical/hreflang or Product/Offer/review
structured data. SSR/SSG and public catalog SEO need a separate decision.
Fixed media frames and out-of-flow quantity-limit notices avoid layout jumps.
One-shot favorite-add, cart-add badge and photo-change effects respect reduced
motion. Existing photo hover and button colour feedback are preserved.

Search suggestions are immediate local matching/ranking of all seven storefront
fixture text: exact/prefix names first, at most five, with IME-aware combobox
keyboard control and no query logging, behavioral analytics, polling or fake HTTP.
Search submit uses the same matcher. A future real endpoint would need a roughly
200–300 ms debounce, cancellation plus latest-result ownership, bounded results
and distinct loading/error/empty states; no such endpoint is implemented here.

Creastrix remains the MVP seller/merchant; Loop Studio is only a demo design author.
Personalisation opens the [local creative studio](personalisation.md). Checkout is a separate
interface prototype; payment, product/Workspace API, account linking, a new approved
domain model and rollout are not delivered. Existing `/login`, `/confirm-email`,
`/account`, authentication lifecycle and fixed development proxy are unchanged.
