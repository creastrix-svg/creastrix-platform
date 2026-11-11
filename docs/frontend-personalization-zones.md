# Personalisation studio: configured areas

Status: owner-approved product direction, demonstrated in the integrated local storefront.
This document is not an approved domain specification.

## Buyer experience

The buyer personalises a prepared product, not its engineering rules. An authorised
product author prepares areas on the drawing and corresponding 3D surfaces. Each
area offers particular content tools and manufacturing operations. The listing
can offer fewer options than the product permits, never extend its technical limits.

Keep three things separate:

- **Area:** where the buyer's design may appear.
- **Content:** text, a picture, a prepared pattern or an AI-assisted idea.
- **Operation:** printing, engraving, cutting or a later supported process.

The buyer selects an area only when there is more than one, then sees its available
tools. The route selects the product; the editor has no demonstration-product
switcher or sample text cards. Switching areas preserves each area's draft. Unsupported tools are absent.
In Luma's 3D preview, a click or tap on a prepared surface selects its area and
highlights it; dragging rotates the model instead. The same selection works in
the expanded view. Named area buttons remain available for keyboard access.
Mouse hover briefly highlights an available area in warm amber; the selected
area retains its stronger green highlight. Hover never changes the tools or draft.
The 2D view exposes the same panel and base as selectable areas, with a dashed
hover outline and a solid selected outline/check. Keyboard and touch selection
remain available without requiring hover. Both views share one selected area.
Selecting the current area again leaves its active tool and pending edits intact.
Manufacturing support such as connecting bridges is part of the prepared pattern,
not a buyer toggle. A future automatic adaptation that changes the design must be
shown to the buyer before acceptance.

Personalisation requires explicit availability and usable area configuration.
When unavailable, omit the entire entry from the product card. Its three
feature tiles follow the gallery with no substitute notice or reserved space.
Direct navigation still must not open an unconfigured editor.
Whether the physical product already exists does not by itself enable this feature.

## Current frontend examples

The [studio profiles](../frontend/src/product/studio/studioProducts.ts) are local UI fixtures.
They are not seller records, an authoring dashboard or server-side authorization.

| Example | Area | Operation | Buyer tools |
| --- | --- | --- | --- |
| Loop, existing pot | Front surface | Print | Text, picture, AI idea |
| Luma, plywood lamp | Front panel | Cut-out example, visual composition only | Prepared pattern, text, picture, AI idea |
| Luma, plywood lamp | Base | Engraving | Text, picture |

Loop offers three illustrative ink colours. Luma's base uses one engraving colour.
Each text area supplies its own font list and input limits. Loop offers six built-in
fonts and bounded local custom-font loading. Luma's base offers sans, serif and mono;
its panel offers the bundled Allerta Stencil only. The panel has one line, at most
14 characters, a fixed lower band and size 18–28. Base/Loop allow two lines, up to
32 characters and size 18–32. Visual fit may require shorter text or a smaller size.
The cut example supports Latin letters, digits, spaces and hyphens. Unsupported
characters produce a visible message; the preview never substitutes an ordinary
solid font. Font loading must succeed before cut text is previewed or applied.
These choices demonstrate profile-driven controls, not manufacturing certification.

Loop text and Luma base engraving use shared preview-area coordinates for the
visible outline, position controls and 3D texture mapping. The complete text block
can move to every edge with a small inset; longer or taller text consequently has
less travel than a short word. Measured glyph overhang is included when available.
Luma's base uses its full prepared front face, rather than Loop's smaller rectangle.
Both 2D views support dragging with slider alternatives. Panel text and pictures
stay inside the prepared lower band; they never modify the pattern openings.

Luma's cut-out area combines prepared patterns with a visual text/image composition
below the motif. Panel and base have independent compositions, undo/reset and applied
snapshots. Applying the lamp validates both areas, including any hidden draft.
Arbitrary uploaded pictures are not silently treated as safe cut geometry. AI is
offered only where explicitly configured, independently of the operation; a text-only
area does not gain AI automatically. AI descriptions stay local:
there is no connected generator or Python manufacturing service. The AI tool
states that availability and does not offer prepared examples as generated results.

Each area supports up to eight independently selectable text/image objects in an
ordered composition. Add, edit, delete, reposition, resize and layer-order changes
share that area's bounded 40-step undo/redo history. Text reset affects the selected
text only; object selection is not itself an edit. Prepared motif choices and AI
briefs remain separate controls, not part of object history. Image fit offers free
placement, contain and cover; aspect ratio is preserved and cover crops at the same
area boundary in SVG and model textures. Fit modes are area-relative; free mode
restores independent size/position controls. Every text object is validated before
Apply, including unselected objects and hidden areas. Apply snapshots the whole
ordered composition, not only the selected object. File/font results belong to the
exact selected object and are discarded when that ownership is superseded.

The owner's 2026-11-04 choice includes visual composition on Luma's panel. Its
explicit visual-only capability does not authorize arbitrary image-to-cut conversion,
new contours, bridge-strength validation, manufacturing export or backend changes.
Decoded images are shared by bounded history snapshots in memory. Persistence
stores only current/applied raster assets, not history. Replaced images can remain reachable through undo until history eviction
or storefront unmount; this is not an immediate-memory-release promise.

Both example products have an interactive 3D view. Prepared pattern openings in the
lamp are geometry, while cut text, engraved text and pictures are surface previews,
not newly cut holes or machined engraving depth. The stencil face illustrates
connecting bridges; actual bridge strength, kerf and material suitability still
require production checks. Each new real product will require an appropriate prepared 3D asset
and area mapping; the viewer cannot infer accurate manufacturing geometry from a photo.

Applying a design updates the Loop product's 2D/3D preview. Adding it to the local
preview cart copies the complete ordered composition, including pictures, text,
fonts, fit and transforms. Plain and differently personalised variants have separate
lines; adding an equal design increments its own line. A line has a stable local ID;
editing it uses a detached draft and updates only that line on explicit Save.
Cancel/back leaves the cart and product drafts unchanged. The AI brief is not part
of a cart design, so its control is absent from the line editor. Product briefs remain
unchanged. Stock capacity is shared by every design of the same colour. Remove/undo,
minicart and checkout summaries use the same exact line identity. A visible Loop
design adds a flat €5 per physical item to the variant's current fixture price,
not per layer or per cart line. An empty design costs nothing extra. Applying a
draft changes the product preview, not previously added cart lines. These local
amounts are illustrative, not a production quote or an accepted order.

Current product drafts, applied previews, active cart lines, local pictures and
custom fonts are stored in one versioned IndexedDB snapshot in this browser/origin.
Reload waits for validation and decoding before exposing editing. Luma's two areas
and motif are retained too; Luma is still not a purchasable catalog item. Undo history,
removed-line tombstones, unsaved cart-editor changes, checkout contact fields and
authentication are not persisted. There is no account association, upload or sync.

The envelope is bounded to 24 Mi characters, 48 distinct normalized raster assets,
64 million decoded pixels and three custom fonts. Repeated assets are stored once.
Public uploads retain their 5 MiB/16 MP limits; internal normalized PNG restoration
allows up to 10 MiB with a 1536-pixel edge, because PNG can exceed the compressed
source size. The whole record changes atomically; only transaction completion
means saved. Unsupported/corrupt data is not overwritten, and memory-only continuation
is explicit. Quota/unavailable storage leaves current work usable with a visible
not-saved notice. Writes are serialized, and a concurrent tab's changed record is
not silently overwritten. This is not cross-tab merging; save conflict requires
keeping the current tab open and reconciling deliberately. Browser eviction or
clearing site data can still remove local work. No server durability is promised.

There is no account save, manufacturing export, production quote or commercial
order in this scope. The integrated frontend preview does not implement domain cart/order
contracts or approve the DRAFT Personalization specification.
The buyer-facing layout uses concise editing labels and one collapsed explanation
of these limits. Only Loop shows a local price preview; Luma is not for sale.

## Accepted direction for existing-stock products

On 2026-10-20 the owner explicitly requested that an already manufactured product
can offer personalisation when the seller has enabled it and specified its parameters.
This frontend prototype demonstrates that direction with Loop. No seller configuration
screen or backend behaviour is delivered here.

The current domain baseline needs a separate reconciliation before implementing this
workflow:

- [Ready-Made Product 1.1](domain/ready-made-product.md) is APPROVED and defines
  ordinary stock fulfilment without a new manufacturing process after purchase.
- [Personalization 0.5](domain/personalization.md), [Revision 0.5](domain/revision.md)
  and [Listing 0.9](domain/listing.md) are DRAFT. The current drafts place
  personalisation on a FINALIZED Revision and exclude production customisation of
  ready-made stock from ordinary MVP fulfilment.
- Decorating or engraving existing stock is therefore a hybrid workflow, not an
  already implemented form of ordinary ready-made fulfilment.

Before its backend implementation, independently review the affected specification
changes: the technical base and version of a configured area, processing responsibility,
stock allocation, immutable purchased artwork, price and lead time, validation,
failure/cancellation handling and fulfilment. This decision does not alter the
approved stock invariants or historical purchase snapshots.

For Revision-based products, the current draft's boundary remains relevant: changing
product-defining areas or constraints requires a new Revision. The commercial offer
may narrow them. A manufacturer profile alone is neither permission to edit that
design nor approval of a particular product.

## Next boundaries

The seller-side area editor, precise drawing-to-3D mapping, image vectorisation,
material/process-dependent geometry checks, AI generation and reproducible production
files require separate implementation. Visual fit is not manufacturing approval.
The frontend examples are a way to validate the buyer interaction before those
production contracts are designed and approved.
