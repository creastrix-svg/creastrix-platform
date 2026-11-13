# Merging a guest cart at sign-in

**Original design proposal.** Implementation-baseline statements below describe
the starting point. For the supported subset in today's synthetic local
storefront, see the [runbook](account-cart-local-run.md). The owner selected three
rules: add quantities for the same variant and design, keep different designs
separate, and never count the same guest contribution twice. The behaviour
below is a proposed contract for implementing those rules. It does not approve
a Cart domain entity, schema, endpoint, or account write.

## What the buyer sees

- Merge only items the guest actually put in the cart. A favourite, unapplied
  studio draft, quantity left on a product page, or row marked removed is not
  an addition. Existing account-only lines stay as they are.
- Match on the **purchasable variant and the applied design**. Two plain units
  of the same variant match. Plain and personalised never match, even when
  personalisation is free. Different colours remain separate variants.
- For a match, add the guest quantity to the current account quantity and keep
  the account line's identity. Otherwise add a separate line with its design
  intact. Use a stable offer/variant identity, not product titles, translated
  colour names, the displayed price, row order or a browser's line number.
  Count each contributing row once. If editing previously left two identical
  account lines, combine only that touched group, keeping the earliest account
  line (stable line ID breaks ties). An empty guest cart changes nothing.
- “Same design” means the same ordered, applied configuration for the same
  product template/version: configured zones and operations where applicable,
  actual text, font, colour, placement, size, artwork and fit. Ignore editor
  selection, undo history, layer IDs and local filenames. Two identical custom
  fonts or images must not differ just because their local handles differ;
  the same filename or handle alone does not prove identical content. Equality
  must be defined by a versioned, validated representation before enabling
  writes. Missing assets or unreadable designs must not be converted to plain.
- Keep requested quantities visible when the combined demand exceeds current
  stock. Show the available amount and ask the buyer to adjust before checkout;
  do not silently reduce quantities or pick which engraving to sacrifice.
  Stock is shared across the lines of a variant. An unlisted variant remains
  identifiable but unavailable and outside the payable total. Recalculate from
  current catalog prices; importing items neither reserves stock nor fixes a price.

## Import once, not once per login

1. Freeze one batch of **unimported guest additions** with a durable import ID
   before sending it. Tabs sharing that guest cart must reuse the same pending
   batch and ID. Sign-in itself is not an addition. Two independent guest carts
   may contain identical items and both represent genuine additions.
2. Bind the batch to the intended, server-authenticated internal User. A different
   account cannot receive or discover it, including after logout or an account
   switch. Never populate a new guest cart from the account cart at logout.
3. On the server, apply the batch to the **current** account cart and record its
   receipt in one transaction. Concurrent imports cannot overwrite an intervening
   cart edit. A committed batch replayed with the same contents has no further
   effect. A reused ID with different contents or a different owner is a conflict,
   not a fresh import. After a replay, show the current cart, not a cached total.
4. Acknowledge only the captured guest additions after a durable success.
   If the response is lost, keep that batch pending and retry the **same** ID.
   Later additions stay separate: if 2 units were sent and the buyer adds 1
   while waiting, only the new 1 remains to import. Until the outcome is known,
   show “sync pending” and keep the captured units read-only; new additions can
   still be made. After confirmation a removal, reduction or design change is
   an ordinary account-cart edit, not a new import. Never clear the whole
   browser store, upload unrelated drafts, or import an account-owned quantity.
   On validation failure apply nothing; retain the guest data for correction.
   The server must distinguish a valid first import from a forgotten/expired
   receipt. If it cannot, do not add any units; reconcile the outcome instead.
   Never retry an uncertain or expired batch under a new ID automatically.

## Acceptance examples

| Account before | Guest or event | Expected outcome |
| --- | --- | --- |
| Blue, plain ×2 | Blue, plain ×3 | One blue plain line ×5. |
| Two lines with identical design, ×2 and ×3 | Same design ×1 | One line ×6 with the earliest account line ID, not ×7. |
| Blue, “Mira” ×2 | Blue, identical “Mira” ×1; blue, “Leo” ×1; blue, plain ×1 | Three lines: “Mira” ×3, “Leo” ×1, plain ×1. |
| Blue, “Mira” ×1 | Red, “Mira” ×1; blue, “Mira” in a different font ×1 | Three separate lines. |
| Empty | Same guest batch of 2 arrives from two tabs; response is lost; sign-in is repeated | Two units in total. The guest sees the confirmed result on retry. |
| Empty | Design A ×2 pending; buyer wants B instead | A stays read-only until confirmed. Editing that confirmed line to B yields B ×2, not A ×2 plus B ×2. |
| Previously imported cart | Delayed replay after the receipt becomes unverifiable | No addition; surface an unresolved import rather than guessing. |
| Last import yielded 5; buyer then reduced it to 4 | That same import is retried | Still 4. A second device signed into this account reads 4. |
| Empty | Two independent guest batches of 2 blue plain units each | Four units, not two. |
| Blue, plain ×2 | Blue, engraved ×2; stock now 3 | Requested 4 across two lines; show 3 available and require adjustment. |
| Account A has a pending import | Buyer signs into B on the same browser | B sees only its own cart; A's batch is neither applied nor shown to B. |

## Where implementation starts

The [storefront ownership rule](../../frontend/docs/storefront.md) assigns the
future cart to the internal User, not a login provider or workspace. Today the
[local reducer](../../frontend/src/product/demoStore.ts) already groups
matching configurations within a browser. Its [design key](../../frontend/src/product/studio/session/designSnapshot.ts)
and local cart row IDs are not server identities. The [connected catalog](../local-catalog.md)
is a separate read-only profile. The current [account-pilot disposition](../authentication-pilot.md#open-cookie-follow-up)
does not authorise business writes. Before implementing an account cart, agree
the offer/asset identity contract, atomic import and receipt lifetime, and the
account-write admission rule; review those decisions independently. Do not join
the catalog and sign-in profiles or reinterpret the [draft commerce specs](../domain/README.md)
as approved. Checkout, payments and stock reservations are outside this slice.
