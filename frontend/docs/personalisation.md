# Personalisation studio

`/products/loop/personalize?variant=cobalt` opens an area-based local studio.
The product is selected by the route; there is no in-editor demonstration switcher.
The existing `?product=lamp` query on that route opens the lamp fixture directly. Each product profile supplies its available areas, operations, tools, fonts and
colours. Loop offers text, pictures and AI ideas on its front surface. Luma offers
prepared cut-out patterns, visual stencil text/pictures and AI ideas on its front panel, and text/pictures on
its base. Switching areas preserves their separate drafts. An unavailable or
unconfigured profile exposes no active personalisation entry or editor tools.
These are local fixtures, not a seller configuration API.

The AI service is not connected: its brief stays local and a concise availability
message explains this beside the field. No example gallery substitutes for generated
results. Prepared lamp patterns remain in the configured pattern tool. There is no
paid generation, provider call or Python manufacturing service in this slice.

The Loop text editor has a live front sketch, six font styles, three ink colours,
size and bounded position controls, drag with slider alternatives, grouped undo/redo,
comparison without text and a confirmed, undoable reset.
Input is limited to 32 complete graphemes and two lines; overflowing text remains
editable but cannot be applied. The visual working area is a UI fixture, not an
approved production constraint or `Personalization` domain record.
Loop text and Luma base text share their respective area bounds with the visible
frame and texture mapping. Position limits account for the whole measured text
block and glyph overhang, with a small inset at the edges. The base uses its full
front face; both 2D sketches support dragging and slider alternatives. Panel cut
text stays in its fixed, visibly marked lower band.

Apply creates a separate product-preview snapshot, rendered on the Loop product
card in 2D and 3D. Drafts/applied snapshots remain per colour and separately per
Luma area. Returning preserves colour and scroll; removing an applied preview keeps
its draft. Add to cart copies the entire applied composition into a stable local
line. Equal designs of the same variant add to that line; different designs and plain
items stay separate. Exact-line editing owns a detached draft: explicit Save updates
only that line, while cancel/back leaves both the cart and product draft unchanged.
Opening an active Loop line by its image or name carries both the finish and
the cart line ID. Its product card reads the line's saved design, quantity and
unit price, not the current catalog preview for that colour. A plain line
stays plain even when the same finish has a separate applied catalog design.
A missing or mismatched line cannot silently open a different configuration.
Returning from the review list or review form keeps the same cart line context.
Removing personalisation on this card converts only that line to the plain
finish and removes its per-unit fee. The buyer stays on the product card, now
bound to that plain line; “View cart” opens the cart only when chosen. If a plain line for that finish already
exists, its quantity increases by exactly the converted quantity. Other
designs and the catalog draft are untouched. Clearing the last decoration in
the cart editor previews the base price; **Remove personalisation** commits
that change, whereas leaving the editor without saving keeps the saved design.
The AI brief is product-local and not part of a cart design; line editing exposes
only text/image controls. All design lines share the variant's stock capacity.
Minicart, remove/undo and checkout summaries preserve those identities and amounts.

`creastrix-studio-local` IndexedDB version 1 stores one atomic bounded snapshot:
current/applied documents, deduplicated PNG resources, custom font bytes, prepared
motifs/local briefs and active cart lines. It excludes undo histories, removal
tombstones, unsaved cart-editor edits, checkout contact fields and private account
state. Existing preferences retain their key/shape; live plain cart lines are not
overwritten by hydration. There was no historical persisted cart schema to migrate.
Restoration validates and decodes before enabling editing, remapping runtime font,
image and object counters without collisions. No network/storage URL or executable
image payload is accepted. Luma drafts persist without making Luma purchasable.
Returning from the account shell remounts the resource library without replacing live
cart quantities or identities. Custom font IDs are rebound only to the exact
previous font bytes; a different tab's coincident ID cannot substitute another
font. An unavailable resource fails visibly rather than silently using a fallback.

The record is limited to 24 Mi characters, 48 distinct raster assets, 64 million
decoded pixels and three custom fonts. Normalized PNG restoration has a separate
10 MiB/1536-pixel-edge limit; the public compressed upload remains 5 MiB/16 MP.
Cart lines share this record-size limit; there is no separate line-count cap.
If a plain SKU disappears from the connected catalog, its key and quantity remain
in the saved record. The cart lists it separately, excluded from totals and checkout,
until the buyer removes it or a later visit finds it listed again. This does not
discard other items or drafts, or treat malformed stored data as an offer.
Writes are queued; saved means transaction completion, not request success. A
compare-and-write in the same transaction refuses to overwrite a changed record
from another tab. This is single-tab editing with conflict detection, not cross-tab
merge. A late old save cannot mark a newer failed/oversized draft saved. Corrupt or
unsupported stored state is retained, with explicit memory-only continuation.
Quota, unavailable storage and serialization failure show a not-saved notice while
keeping live work usable. Browser site-data clearing/eviction is outside this local
durability boundary. This browser-only preview does not call account or order endpoints.

The separate local shared-catalog storefront offers **Save design**. The buyer
can reopen a saved composition from **Account → My designs**, including its
pictures, text and fonts. Saving does not add a cart item. **Apply design** and
**Add to cart** select the version to buy; subsequent library edits leave the
cart version intact. See the [account walkthrough](../../docs/engineering/account-cart-local-run.md).
Loop uses one illustrative €5 surcharge per personalised unit, independent of
the number of text or picture layers. The studio previews the draft's unit
price; the product uses the applied design and each cart line uses its own saved
design. For example, two personalised Cobalt units cost 2 × (€24 + €5) = €58.
The current variant price is used, including sale prices. Refresh recomputes
amounts from the local fixtures; prices are not persisted or guaranteed.
Luma has no purchase price. No server quote, payment or order is created.

Each configured area now owns an ordered composition of up to eight text/image
objects. Selection, addition, deletion, layer order, resizing and dragging use one
canonical composition; SVG and 3D texture adapters consume the same object order
and zone-relative image bounds. Object edits share a 40-step per-area history;
selection is not an edit and text reset affects only the selected object. Prepared
motifs and local AI briefs remain outside that object history. Apply validates all
text objects in both lamp areas and snapshots the complete composition. Image modes
are free, contain and cover; contain/cover use the complete zone, keep aspect ratio
and crop cover without stretching. File/font request ownership includes object ID,
zone and a supersession epoch. Undo/delete/selection cannot redirect a late result.
Image assets are shared by history snapshots and may remain retained for undo;
the mounted-storefront lifetime, object cap and history bounds limit retention.

The 3D view reuses the existing lazy local viewer and its inline/expanded controls.
The four `studio-loop-*.glb` assets use the same pot geometry as the product
models and separate the outer wall so lettering and images stay off the rim and
interior. Two reusable canvas textures decorate that wall without replacing its
normal map. The sketch shows the same profile in a fixed front view; it guides
placement, not exact print dimensions. If a model fails to load, 2D remains available.
GPU resource lifetime remains controlled by the renderer; canvas backing stores
and material bindings are cleaned up, without claiming immediate GPU disposal.
After changing `loopShape.json`, regenerate all eight Loop assets and check
them from the repository root:

```sh
node scripts/generate-personalization-models.mjs
node --test scripts/generate-personalization-models.test.mjs
```

Pictures are decoded locally: PNG/JPEG/WebP up to 5 MiB and 16 megapixels, with a
header-dimension guard before decoding and an 8192-pixel edge limit. The resulting
canvas is reduced proportionally to a maximum edge of 1536 and normalised to PNG.
Reading and decoding each have a 12-second bound. Cancelled or obsolete results
cannot replace a draft; failed replacement preserves the previous picture. SVG,
remote image URLs and production vectorisation are outside this upload control.

Loop's six font styles include locally bundled Nunito, Cormorant Garamond and Caveat;
licenses, pinned sources and hashes are in `public/fonts/`. Where the configured
area permits custom fonts, buyers can add up to three TTF/OTF/WOFF2 files per mounted
studio session, each at most 2 MiB. Luma's base offers only sans, serif and mono;
its front panel offers the bundled Allerta Stencil. A file
header check precedes browser FontFace decoding, with an eight-second bound and
owned cleanup. Unique accepted font IDs preserve applied previews and text undo.
In the browser-only preview font files are not transmitted. Rights and available glyphs remain the buyer's
responsibility; browser fallback is not proof of a usable manufacturing font.

Luma is a plywood-lamp example, not another purchasable catalog item. Its prepared
botanical, forest and orbit cut-out patterns include their connecting material.
The buyer cannot remove or tune bridges. The panel offers prepared patterns plus
optional stencil text in a reserved lower band (one line, at most 14 Latin letters,
digits, spaces or hyphens). Font loading and visual fit are checked before applying;
unsupported glyphs are reported rather than replaced by a fallback font. Arbitrary
pictures are not converted to cut geometry. The base separately accepts text and
pictures as visual engraving references. The panel also permits a visual image/text
composition in the same lower band, explicitly not an image-to-cut service. Both
composition drafts and undo histories remain
independent; applying validates both areas, even when one is not selected.
The 3D preview contains the lamp assembly and pattern openings; surface previews
do not create new text-shaped holes or simulate engraving depth. Neither the model
nor visual fit certifies material-dependent bridge strength,
manufacturing validity or a production-ready cutting file.
The three `studio-lamp-*.glb` files share their prepared pattern descriptors with
the flat panel view. Their sampled, extruded openings are display geometry rather
than an engineering mesh; the warm inner diffuser is an illustrative material.
Generate/check these fixtures from the repository root with:

```sh
node --experimental-strip-types scripts/generate-lamp-models.mjs
node --experimental-strip-types --test scripts/generate-lamp-models.test.mjs
```

The studio supports all seven storefront languages and light/dark appearance.
Connected AI, computer vision/vector validation, seller-side area authoring,
authoritative manufacturing limits, public account designs, commercial cart/order
integration and manufacturing export require later, separately approved slices.
The owner's accepted direction includes configured personalisation on existing
stock, but that hybrid workflow is outside the current APPROVED Ready-Made Product
scope and DRAFT Personalization/Listing rules. See the
[configured-area decision and domain gap](../../docs/frontend-personalization-zones.md).
Current fixtures do not approve those drafts or change backend behaviour.
