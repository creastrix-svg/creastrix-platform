# Account cart: persistent storage and local activation

**Storage rules accepted by the owner on 2026-11-12. Implementation and
end-to-end verification are separate.** The [buyer rules](guest-cart-merge.md)
and [import contract](account-cart-import.md) apply. Signing in and recovering
access are not shopping actions.

## Accepted storage decision

Use a persistent server catalog and private design storage, not a test-only
table or the browser as the catalog of record. The product card and cart refer
to the same stable offer, variant and versioned template. Loop and Tide are
records, not special cases. The team responsible for a product manages its
catalog data within its authorised Workspace. That responsibility does not
confer intellectual-property rights or access to a buyer's source files.
Changing a template does not rewrite an existing design; withdrawing a product
leaves it identifiable in the cart but not payable.

A buyer's saved design and uploaded images/fonts are private to the internal
User, not an email address, external provider identity or seller. PostgreSQL
records versions, ownership, durable references, cart rows and import receipts;
the bytes live in private persistent file storage. A saved design and its
applied version in a cart are separate references: removing either one does
not break the other. A pending guest transfer also holds a reference. In one
database transaction, successful import attaches the cart references, updates
quantities and records the receipt. Do not upload files during that transaction
or acknowledge missing bytes; keep the guest contribution until confirmed.

| File lifecycle | Accepted clock |
| --- | --- |
| Unfinished upload that has **never** been linked to a design, cart or pending import | Expires 24 hours after the server created the upload. Do not reset the clock on browser refresh. |
| Previously linked file loses its **last** durable reference | Keep it recoverable for 7 days from the committed removal of that reference. Restoring an authorised link in that window cancels pending deletion; any later last unlink starts a new window. |
| A saved design, cart line or pending import still references the file, or the commit outcome is unresolved | Neither clock permits deletion. Reconcile first. |

Use server instants and elapsed durations. The boundary is eligibility, not
a promise of immediate physical deletion. Recheck references and serialize
relink vs cleanup before removing bytes. Never expire a saved design, an
abandoned cart or an import receipt simply because the file deadlines elapsed.
Publish a verified upload as complete and create its first durable reference
in the same metadata transaction. Receiving the last byte is not publication:
an uncommitted upload remains in staging. Neither rule assigns a new deadline
to an inconsistent or legacy completed object with no provable reference
history; hold it for reconciliation instead of guessing its age.
These durations do **not** decide account erasure, backup retention or receipt
compaction. Those decisions precede use with real customer files. This decision
authorises local implementation; it does not promote the draft commerce domains
or open the existing account pilot for live business writes.

## Accepted local test sign-in write scope

On 2026-11-12 the owner authorised **only the separate local synthetic sign-in**
to import a guest cart and recover the cart binding in the same session. Use
the existing React storefront, a loopback test identity provider and a dedicated
PostgreSQL/private-file store. The default build, real Cognito account pilot,
public users, staff and checkout remain outside this authorisation.

The owner subsequently authorised one additional local shopping action: a buyer
already signed in to this synthetic profile may add a plain enabled variant
(initially Tide / Cobalt blue). This is not another guest transfer. Its independent
action ID, exact quantity and committed cart version make a retry distinguishable
from a new addition. It grants no write access to the separate real account pilot.

The owner extended this local slice to the **same catalog the buyer browses**:
plain additions use available published variants, not a manually maintained
per-SKU allowlist. An applied personalisation joins the purchase only as a
verified immutable version owned by this buyer and compatible with the offer
and variant. A plain line and a personalised line stay distinct. This decision
is limited to the local synthetic sign-in and does not authorise orders or payments.

In the same local storefront the owner authorised editing an existing account
cart row. Quantity sets an absolute number. **Remove** immediately excludes the
row from the subtotal and stock demand. **Restore** is offered only during that
visit to the full cart, for the former quantity and design, subject to current
availability. Refreshing or leaving and reopening the cart shows only active
rows. The separate **×** can dismiss the removed row sooner. Neither action deletes the buyer's
saved design or uploaded files. These actions are not a new guest import.

### My designs (local buyer library)

The owner authorised a private design library for the same local shared catalog
and synthetic buyer admission. **Save design** records the studio composition as
an account entry without applying it or writing cart units. Each entry points
to an immutable version with its private image/font references. Subsequent edits
advance the entry's version; a stale editor cannot silently replace a newer
version. Copy creates a separate entry. Deleting an entry removes it from the
library, not from an existing cart, and does not promise physical deletion of
the underlying bytes. No automatic file collector is enabled by this change.

The guest flow stages only the particular draft the buyer explicitly chose.
One browser-wide transaction reserves that temporary draft for one login,
even if the login tab was duplicated. Only the tab with the matching completed
login can offer to save it. A second explicit action confirms the save; neither
login nor opening the account silently adopts a draft or purchases a product.
This is separate from guest cart import, staff permissions and real Cognito.

For this single-instance test profile the server assigns one shared write
context for switchable test sign-ins. A new sign-in or logout retires the
previous generation. Every import mutation must serialize its original
admission and current internal User eligibility with the cart/receipt commit.
A session cookie, matching email, supplied user ID or UI check is not a write
grant. A retry after uncertain delivery must consult the original batch's
durable outcome. No receipt compaction, ID reuse or restore from an unverified
backup is assumed in this slice.

Recovering access after a known binding rollback must not import guest units
or require another login: first attempt automatically, then offer a manual
retry if that attempt fails transiently. Retry of a failed cart **read** is a
GET only. Demonstrate these separately in Chrome with actual rows and receipts;
the owner's permission to implement them is not a claim that either passed.

### Two local buyers: never guess the owner

On 2026-11-12 the owner separately authorised fixing and testing handoff with
**two specified synthetic buyers**, not adding a second real account. If the
browser cannot persist which buyer owns a guest contribution, a later login
must not receive, show, or acknowledge that contribution. A failed write is
not proof that a basket belongs to the next account.

Before native sign-in, atomically detach the guest lines under a fresh
single-use intent and start an empty editable guest partition. Do not start
that sign-in if sealing fails. The local server reserves the opaque intent for
that exact login attempt; it is not an account ID or an access credential.
The authenticated session can confirm its own intent, after which the browser
can bind the exact detached lines to the verified internal User. A login
without matching proof can read its own cart and resume its own already-bound
batches, but not adopt another login's sealed lines or the current guest cart.

If the owner write fails, keep the detached contents local. If the original
session is still available it may retry; if the proof is gone, retain the
record rather than guessing a new owner, even when the same person returns.
Older unowned data without reliable provenance is not eligible for automatic
import. The same rule applies to a stale tab whose edit did not save before
the guest partition changed: even a positive quantity difference is not a
new guest addition for the next buyer. Preserve the disputed edit separately
when storage is available; never write it over the successor cart or use it
as an import source. No buyer-facing orphan-recovery flow is implied.
Test the failure and tab closure in Chrome, then both a no-transfer
case and a genuine contribution by the other buyer. Verify both carts and
their receipts in the dedicated database. This does not authorise multi-user
rollout, real Cognito access, or deletion of ambiguous data.

## Reusable now

- The backend [line planner](../../backend/src/main/java/com/creastrix/platform/accountcart/GuestCartMergePlanner.java)
  accepts the current lines of one owner and one **previously uncredited**
  contribution. Using server-resolved offer, variant and plain/validated design
  identity, it totals only touched groups. It does not canonicalise uploads,
  authenticate callers, create receipts or guarantee replay safety by itself.
- The real React cart route can render an explicitly bound [account reader](../../frontend/src/product/cart/accountCartAdmission.ts).
  Only a pending, owner-bound recovery ticket from a classified binding failure
  permits one automatic recovery. A retryable failure exposes **Retry** with
  the same ticket. Success triggers a fresh owner read; zero lines means empty
  only if that read succeeds. No guest data is consumed. A different
  authentication generation retires the old reader even for the same User.
  The standard entry point does not install an account transport.
- The isolated stand demonstrated a **known rollback** of the binding, one
  login, preserved line identities and no imports. Its sample SKUs, manually
  built design keys, SQL fixtures, fault hooks, UI and provider are not
  production inputs. It did not establish recovery from an unknown commit,
  asset equivalence, real storefront imports or multi-instance write admission.

## Implementation gates before enabling account access

1. **Catalog authority.** Recommend a versioned projection owned by the commerce
   write store: stable offer + within-offer variant IDs, explicit listing status,
   template revision and allowed zones/operations, with authoritative refresh
   and retirement. Keep the development catalog read-only and in its own
   profile; do not trust a storefront title, URL, price, seed or browser key.
   Use the workspace-managed server catalog above; review the mapping and
   version feed rather than treating the development seed as an authority.
   Missing authority rejects the whole import; known retired items remain
   identifiable but not payable.
2. **Design resources.** Recommend owner-authorised immutable staged bytes for
   images and fonts, compared by exact bytes and resource kind/version. A
   pending batch pins those resources; applying transfers durable pins to the
   retained cart lines in the same transaction. Implement the accepted file
   lifetimes above; erasure and backup recovery remain separate. Until supported,
   keep personalised guest lines locally, not as plain lines. Do not silently
   import just the easy rows.
3. **Access and receipt lifetime.** Implement the accepted local test sign-in
   gate above. Verify immutable owner/generation and ACTIVE User through
   commit, not just at entry. Bind recovery to the original session, claim and
   owner; it must never prepare or apply an import. Unknown outcomes require
   authoritative reconciliation. Agree retention/non-reuse after pruning or
   restore. A session cookie, client epoch or hidden UI alone is not authority.

## Acceptance before wiring a live transport

Freeze guest contributions durably once across tabs, bind to the intended
User, then prepare/apply against the current cart. A receipt and its cart
change must commit together; a lost reply, two tabs or a second login must
not add twice. Two genuinely separate contributions must both count. Never
remove guest data until its exact receipt proves consumption. Verify stale
account switches, suspension, rollback and retry with real transactions.
Then verify the real storefront including header, checkout boundary, draft
assets and an intervening guest edit. Preserve the guest-only build. These
tests and decisions precede activation; no combination of the local catalog
and authentication profiles, public access, checkout writes or rollout follows
from this transfer.
